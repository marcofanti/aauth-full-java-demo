package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.TokenException;
import io.github.marcofanti.aauth.demo.a2a.A2aJson;
import io.github.marcofanti.aauth.headers.AAuthHeaders;
import io.github.marcofanti.aauth.metadata.Metadata;
import io.github.marcofanti.aauth.resource.ChallengeBuilder;
import io.github.marcofanti.aauth.resource.RequestVerifier;
import io.github.marcofanti.aauth.signing.HttpSignatureException;
import io.github.marcofanti.aauth.signing.JwksFetcher;
import io.github.marcofanti.aauth.signing.SignatureBase;
import io.github.marcofanti.aauth.signing.SignatureKeyHeader;
import io.github.marcofanti.aauth.signing.SignatureVerifier;
import io.github.marcofanti.aauth.signing.VerifyRequest;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import io.github.marcofanti.aauth.tokens.AgentTokens;
import io.github.marcofanti.aauth.tokens.AuthTokens;
import io.github.marcofanti.aauth.tokens.PersonTokens;
import java.net.URI;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inbound AAuth verification for an agent's A2A endpoint (AAuth draft-11), at one of three
 * requirement levels:
 *
 * <ul>
 *   <li>{@link Requirement#PSEUDONYM} — any valid RFC 9421 signature ({@code hwk}). The
 *       library's resource verifier accepts only the {@code jwt} scheme, so this level verifies
 *       the HTTP signature and the RFC 9530 body digest directly;
 *   <li>{@link Requirement#IDENTITY} — a valid {@code aa-agent+jwt};
 *   <li>{@link Requirement#AUTH_TOKEN} — an {@code aa-auth+jwt} granting the scope. A caller
 *       presenting its agent token is asked for a person token ({@code requirement=person-token});
 *       a caller presenting a person token gets a resource token, derived from it, to exchange
 *       at the person's PS.
 * </ul>
 *
 * <p>The target URI is rebuilt from the configured canonical base (SPEC §10.3.1) — never from
 * the request's Host header.
 */
public final class AAuthInboundVerifier {

    public enum Requirement {
        PSEUDONYM,
        IDENTITY,
        AUTH_TOKEN
    }

    private final RequestVerifier verifier;
    private final URI canonicalBase;
    private final ChallengeBuilder challenges;
    private final Requirement requirement;
    private final KeyPair resourceKeyPair;
    private final String resourceKid;
    private final String resourceId;
    private final String requiredScope;

    /**
     * Builds a verifier for a {@code demo.aauth.mode} value: {@code hwk} → pseudonym,
     * {@code jwt} → identity, {@code auth-token} → auth token, {@code consent} → auth token
     * with {@code require:user} appended to the resource-token scope.
     *
     * <p>The resource key pair must be stable across restarts (see {@link StableKeys}) — the
     * Person Server caches resource JWKS by issuer, so a fresh key per process would fail
     * resource-token verification after a restart.
     *
     * @param personServer the trusted Person Server identifier (an {@code https} origin); the
     *     audience of issued resource tokens
     */
    public static AAuthInboundVerifier forMode(
            URI canonicalBase,
            String mode,
            String scope,
            String personServer,
            KeyPair resourceKeyPair,
            String resourceKid) {
        Requirement requirement =
                switch (mode) {
                    case "auth-token", "consent" -> Requirement.AUTH_TOKEN;
                    case "jwt" -> Requirement.IDENTITY;
                    default -> Requirement.PSEUDONYM;
                };
        String effectiveScope = "consent".equals(mode) ? scope + " require:user" : scope;
        return new AAuthInboundVerifier(
                canonicalBase,
                resourceKeyPair,
                resourceKid,
                requirement,
                AAuthHttp.jwksFetcher(),
                effectiveScope,
                personServer);
    }

    /**
     * @param requiredScope scope for issued resource tokens (append {@code require:user} to
     *     trigger the Person Server's consent flow); its first value is the scope an auth token
     *     must grant
     * @param personServer the trusted Person Server identifier
     */
    public AAuthInboundVerifier(
            URI canonicalBase,
            KeyPair resourceKeyPair,
            String resourceKid,
            Requirement requirement,
            JwksFetcher jwksFetcher,
            String requiredScope,
            String personServer) {
        this.canonicalBase = canonicalBase;
        this.requirement = requirement;
        this.resourceKeyPair = resourceKeyPair;
        this.resourceKid = resourceKid;
        this.requiredScope = requiredScope;
        this.resourceId = trimTrailingSlash(canonicalBase.toString());
        this.verifier = RequestVerifier.builder(List.of(canonicalBase.getRawAuthority()), jwksFetcher)
                .resourceId(resourceId)
                .trustedPersonServers(Set.of(personServer))
                .trustedAuthServers(Set.of(personServer))
                .build();
        this.challenges =
                new ChallengeBuilder(resourceId, resourceKeyPair.getPrivate(), resourceKid, personServer, jwksFetcher);
    }

    /**
     * Verification outcome. {@code agentId} is set for agent-token callers; {@code personSub} for
     * person- and auth-token callers (draft-11 tokens name the person, not the agent).
     */
    public record Verification(Status status, String agentId, String personSub, List<String> scopes, String error) {

        public enum Status {
            VALID,
            INVALID,
            NEEDS_PERSON_TOKEN,
            NEEDS_AUTH_TOKEN
        }

        public boolean valid() {
            return status == Status.VALID;
        }

        static Verification invalid(String error) {
            return new Verification(Status.INVALID, null, null, null, error);
        }
    }

    public Verification verify(String method, String path, Map<String, String> headers, byte[] body) {
        String effectivePath = path == null || path.isEmpty() ? "/" : path;
        String targetUri = canonicalBase.resolve(effectivePath).toString();
        if (requirement == Requirement.PSEUDONYM) {
            return verifyPseudonymous(method, targetUri, headers, body);
        }
        RequestVerifier.Requirements wanted = requirement == Requirement.IDENTITY
                ? RequestVerifier.Requirements.identity()
                : RequestVerifier.Requirements.none();
        RequestVerifier.Result result = verifier.verifyRequest(method, targetUri, headers, body, wanted);
        if (!result.valid()) {
            return Verification.invalid(result.error());
        }
        if (requirement == Requirement.IDENTITY) {
            return new Verification(Verification.Status.VALID, result.agentId(), null, null, null);
        }
        return dispatchOnToken(result);
    }

    private Verification dispatchOnToken(RequestVerifier.Result result) {
        String typ = result.tokenType();
        if (AgentTokens.TYPE.equals(typ)) {
            return new Verification(
                    Verification.Status.NEEDS_PERSON_TOKEN, result.agentId(), null, null, "Person token required");
        }
        if (PersonTokens.TYPE.equals(typ)) {
            return new Verification(
                    Verification.Status.NEEDS_AUTH_TOKEN, null, result.userSub(), null, "Auth token required");
        }
        if (AuthTokens.TYPE.equals(typ) && result.scopes().contains(grantedScope())) {
            return new Verification(Verification.Status.VALID, null, result.userSub(), result.scopes(), null);
        }
        return Verification.invalid("Auth token does not grant " + grantedScope());
    }

    private static Verification verifyPseudonymous(
            String method, String targetUri, Map<String, String> headers, byte[] body) {
        String signatureInput = headerIgnoringCase(headers, "Signature-Input");
        String signature = headerIgnoringCase(headers, "Signature");
        String signatureKey = headerIgnoringCase(headers, "Signature-Key");
        if (signatureInput == null || signature == null || signatureKey == null) {
            return Verification.invalid("Missing signature headers");
        }
        String contentDigest = headerIgnoringCase(headers, "Content-Digest");
        if (contentDigest != null
                && body != null
                && body.length > 0
                && SignatureBase.verifyContentDigest(contentDigest, body) != SignatureBase.DigestCheck.MATCH) {
            return Verification.invalid("Content-Digest does not match the request body");
        }
        try {
            VerifyRequest.Builder request = VerifyRequest.builder(method, targetUri)
                    .headers(headers)
                    .signatureHeaders(signatureInput, signature, signatureKey);
            if (body != null) {
                request.body(body);
            }
            return SignatureVerifier.verify(request.build())
                    ? new Verification(Verification.Status.VALID, null, null, null, null)
                    : Verification.invalid("Signature verification failed");
        } catch (HttpSignatureException | IllegalArgumentException e) {
            return Verification.invalid("Invalid signature: " + e.getMessage());
        }
    }

    /**
     * Challenge for callers that failed verification: {@code Accept-Signature} asking for a
     * pseudonymous signature at the {@code hwk} level, {@code requirement=agent-token} otherwise.
     */
    public ChallengeBuilder.Challenge challenge() {
        if (requirement == Requirement.PSEUDONYM) {
            return new ChallengeBuilder.Challenge(
                    AAuthHeaders.HEADER_ACCEPT_SIGNATURE,
                    AAuthHeaders.buildPseudonymRequirement(List.of("ed25519"), List.of()));
        }
        return challenges.buildChallenge(ChallengeBuilder.Spec.agentToken());
    }

    /** {@code requirement=person-token} challenge for a caller that presented its agent token (§6.4). */
    public ChallengeBuilder.Challenge personTokenChallenge() {
        return challenges.buildChallenge(ChallengeBuilder.Spec.personToken());
    }

    /**
     * Auth-token challenge for a caller that presented a verified person token: the library issues
     * a resource token whose {@code ps}, {@code sub}, {@code presented_jti}, {@code mission_s256}
     * and {@code tenant} derive from that person token, bound to the caller's {@code cnf.jwk}
     * (draft-11 §6.7, RS-30..32).
     *
     * @throws TokenException if the presented token fails verification
     */
    public ChallengeBuilder.Challenge authTokenChallenge(Map<String, String> headers) {
        String presented = presentedJwt(headers);
        if (presented == null) {
            throw new IllegalStateException("Auth-token challenge requires a presented person token");
        }
        return challenges.buildChallenge(
                ChallengeBuilder.Spec.authToken(presented, confirmationKey(presented), requiredScope));
    }

    /** {@code /.well-known/aauth-resource.json} body for this resource. */
    public String resourceMetadataJson() {
        return A2aJson.toJson(Metadata.resource(resourceId, resourceId + "/.well-known/jwks.json")
                .build());
    }

    /** {@code /.well-known/jwks.json} body: this resource's public signing keys. */
    public String resourceJwksJson() {
        return A2aJson.toJson(Jwk.generateJwks(List.of(Jwk.publicKeyToJwk(resourceKeyPair.getPublic(), resourceKid))));
    }

    private String grantedScope() {
        return requiredScope.split("\\s+")[0];
    }

    private static PublicKey confirmationKey(String token) {
        Map<String, Object> claims =
                io.github.marcofanti.aauth.signing.Jwts.parse(token).payload();
        if (claims.get("cnf") instanceof Map<?, ?> cnf && cnf.get("jwk") instanceof Map<?, ?> jwkMap) {
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwkMap.forEach((key, value) -> jwk.put(String.valueOf(key), value));
            return Jwk.toPublicKey(jwk);
        }
        throw new IllegalStateException("Presented token has no cnf.jwk to bind a resource token to");
    }

    private static String presentedJwt(Map<String, String> headers) {
        String signatureKey = headerIgnoringCase(headers, "Signature-Key");
        if (signatureKey == null) {
            return null;
        }
        Object jwt = SignatureKeyHeader.parse(signatureKey).params().get("jwt");
        return jwt == null ? null : String.valueOf(jwt);
    }

    private static String headerIgnoringCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
