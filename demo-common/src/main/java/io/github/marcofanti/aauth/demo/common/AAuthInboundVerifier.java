package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.TokenException;
import io.github.marcofanti.aauth.demo.a2a.A2aJson;
import io.github.marcofanti.aauth.keys.CachingJwksFetcher;
import io.github.marcofanti.aauth.metadata.Metadata;
import io.github.marcofanti.aauth.resource.ChallengeBuilder;
import io.github.marcofanti.aauth.resource.RequestVerifier;
import io.github.marcofanti.aauth.resource.ResourceTokenIssuer;
import io.github.marcofanti.aauth.signing.JwksFetcher;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.SignatureKeyHeader;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import io.github.marcofanti.aauth.tokens.AuthTokens;
import io.github.marcofanti.aauth.tokens.PersonTokens;
import java.net.URI;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Inbound AAuth verification for an agent's A2A endpoint (AAuth draft-11), at one of three
 * requirement levels:
 *
 * <ul>
 *   <li>{@link Requirement#PSEUDONYM} — any valid RFC 9421 signature ({@code hwk});
 *   <li>{@link Requirement#IDENTITY} — a valid {@code aa-agent+jwt} whose issuer keys resolve
 *       via the {@link JwksFetcher};
 *   <li>{@link Requirement#AUTH_TOKEN} — an {@code aa-auth+jwt} carrying scopes. A caller
 *       presenting its agent token is asked for a person token ({@code requirement=person-token});
 *       a caller presenting a verified person token gets a resource token, derived from that
 *       person token, to exchange at the person's PS.
 * </ul>
 *
 * <p>The target URI is rebuilt from the configured canonical base (SPEC §10.3.1) — never from
 * the request's Host header.
 */
public final class AAuthInboundVerifier {

    private static final String AGENT_TOKEN = "aa-agent+jwt";
    private static final String PERSON_TOKEN = "aa-person+jwt";
    private static final String AUTH_TOKEN = "aa-auth+jwt";

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
    private final CachingJwksFetcher tokenKeys = new CachingJwksFetcher();

    /** Pseudonym-level verifier (signature required, no identity). */
    public AAuthInboundVerifier(URI canonicalBase, KeyPair resourceKeyPair, String resourceKid) {
        this(canonicalBase, resourceKeyPair, resourceKid, Requirement.PSEUDONYM, null, null);
    }

    /**
     * Builds a verifier for a {@code demo.aauth.mode} value: {@code hwk} → pseudonym,
     * {@code jwt} → identity, {@code auth-token} → auth token, {@code consent} → auth token
     * with {@code require:user} appended to the resource-token scope.
     *
     * <p>The resource key pair must be stable across restarts (see {@link StableKeys}) — the
     * Person Server caches resource JWKS by issuer, so a fresh key per process would fail
     * resource-token verification after a restart.
     */
    public static AAuthInboundVerifier forMode(
            URI canonicalBase, String mode, String scope, KeyPair resourceKeyPair, String resourceKid) {
        Requirement requirement =
                switch (mode) {
                    case "auth-token", "consent" -> Requirement.AUTH_TOKEN;
                    case "jwt" -> Requirement.IDENTITY;
                    default -> Requirement.PSEUDONYM;
                };
        String effectiveScope = "consent".equals(mode) ? scope + " require:user" : scope;
        JwksFetcher fetcher = null;
        if (requirement != Requirement.PSEUDONYM) {
            CachingJwksFetcher caching = new CachingJwksFetcher();
            fetcher = (id, dwk, kid) -> caching.fetch(id, kid, dwk);
        }
        return new AAuthInboundVerifier(
                canonicalBase, resourceKeyPair, resourceKid, requirement, fetcher, effectiveScope);
    }

    /**
     * @param requiredScope scope for issued resource tokens (append {@code require:user} to
     *     trigger the Person Server's consent flow); required for {@link Requirement#AUTH_TOKEN}
     */
    public AAuthInboundVerifier(
            URI canonicalBase,
            KeyPair resourceKeyPair,
            String resourceKid,
            Requirement requirement,
            JwksFetcher jwksFetcher,
            String requiredScope) {
        this.canonicalBase = canonicalBase;
        this.requirement = requirement;
        this.resourceKeyPair = resourceKeyPair;
        this.resourceKid = resourceKid;
        this.requiredScope = requiredScope;
        this.verifier = new RequestVerifier(List.of(canonicalBase.getRawAuthority()), jwksFetcher);
        this.resourceId = trimTrailingSlash(canonicalBase.toString());
        this.challenges = new ChallengeBuilder(resourceId, resourceKeyPair.getPrivate(), resourceKid);
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
        // Draft-11 person and auth tokens carry no agent identifier, so "identity required" only
        // applies at the IDENTITY level; AUTH_TOKEN dispatches on the presented token type below.
        boolean requireAgentIdentity = requirement == Requirement.IDENTITY;
        RequestVerifier.Result result =
                verifier.verifyRequest(method, targetUri, headers, body, requireAgentIdentity, false);
        if (!result.valid()) {
            return Verification.invalid(result.error());
        }
        if (requirement != Requirement.AUTH_TOKEN) {
            return new Verification(Verification.Status.VALID, result.agentId(), null, null, null);
        }
        String token = presentedJwt(headers);
        String typ = token == null ? null : String.valueOf(Jwts.parse(token).header().get("typ"));
        if (AGENT_TOKEN.equals(typ)) {
            return new Verification(
                    Verification.Status.NEEDS_PERSON_TOKEN, result.agentId(), null, null, "Person token required");
        }
        if (PERSON_TOKEN.equals(typ)) {
            return verifyPersonToken(token);
        }
        if (AUTH_TOKEN.equals(typ)) {
            return verifyAuthToken(token, result);
        }
        return Verification.invalid("Auth token required; the request carries no agent, person or auth token");
    }

    private Verification verifyPersonToken(String token) {
        try {
            Map<String, Object> claims = PersonTokens.verify(token, issuerKeys(token), resourceId);
            return new Verification(
                    Verification.Status.NEEDS_AUTH_TOKEN,
                    null,
                    String.valueOf(claims.get("sub")),
                    null,
                    "Auth token required");
        } catch (TokenException e) {
            return Verification.invalid("Invalid person token: " + e.getMessage());
        }
    }

    private Verification verifyAuthToken(String token, RequestVerifier.Result result) {
        try {
            AuthTokens.verifyToken(
                    token, issuerKeys(token), new AuthTokens.VerifyOptions(AUTH_TOKEN, null, resourceId, null));
        } catch (TokenException e) {
            return Verification.invalid("Invalid auth token: " + e.getMessage());
        }
        if (result.scopes() == null || result.scopes().isEmpty()) {
            return Verification.invalid("Auth token carries no scopes");
        }
        return new Verification(Verification.Status.VALID, null, result.userSub(), result.scopes(), null);
    }

    /** Challenge for callers that failed signature/identity verification. */
    public ChallengeBuilder.Challenge challenge() {
        return challenges.buildChallenge(
                requirement == Requirement.PSEUDONYM
                        ? ChallengeBuilder.Spec.pseudonym()
                        : ChallengeBuilder.Spec.identity());
    }

    /** {@code requirement=person-token} challenge for a caller that presented its agent token (§6.4). */
    public ChallengeBuilder.Challenge personTokenChallenge() {
        return challenges.buildChallenge(ChallengeBuilder.Spec.personToken());
    }

    /**
     * Auth-token challenge for a caller that presented a verified person token: issues a resource
     * token whose {@code aud}/{@code ps}, {@code sub}, {@code presented_jti}, {@code mission_s256}
     * and {@code tenant} come from that person token, bound to the caller's {@code cnf.jwk}
     * (draft-11 §6.7, RS-30..32).
     */
    public ChallengeBuilder.Challenge authTokenChallenge(Map<String, String> headers) {
        String personToken = presentedJwt(headers);
        if (personToken == null) {
            throw new IllegalStateException("Auth-token challenge requires a presented person token");
        }
        Map<String, Object> claims = Jwts.parse(personToken).payload();
        String ps = String.valueOf(claims.get("iss"));
        ResourceTokenIssuer.Grant grant = new ResourceTokenIssuer.Grant(
                confirmationKey(claims),
                requiredScope,
                ps,
                ps,
                String.valueOf(claims.get("sub")),
                String.valueOf(claims.get("jti")),
                null,
                stringOrNull(claims.get("mission_s256")),
                stringOrNull(claims.get("tenant")));
        return challenges.buildChallenge(ChallengeBuilder.Spec.authToken(grant));
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

    /** JWKS resolution for a PS-issued token: {@code {iss}/.well-known/aauth-person.json}. */
    private Function<String, Map<String, Object>> issuerKeys(String token) {
        Jwts.Decoded jwt = Jwts.parse(token);
        String kid = String.valueOf(jwt.header().get("kid"));
        String dwk = String.valueOf(jwt.payload().getOrDefault("dwk", "aauth-person.json"));
        return iss -> tokenKeys.fetch(iss, kid, dwk);
    }

    private static PublicKey confirmationKey(Map<String, Object> claims) {
        if (claims.get("cnf") instanceof Map<?, ?> cnf && cnf.get("jwk") instanceof Map<?, ?> jwkMap) {
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwkMap.forEach((key, value) -> jwk.put(String.valueOf(key), value));
            return Jwk.toPublicKey(jwk);
        }
        throw new IllegalStateException("Presented token has no cnf.jwk to bind a resource token to");
    }

    private static String presentedJwt(Map<String, String> headers) {
        String signatureKey = headerIgnoringCase(headers, "Signature-Key");
        return signatureKey == null
                ? null
                : SignatureKeyHeader.parse(signatureKey).params().get("jwt");
    }

    private static String headerIgnoringCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
