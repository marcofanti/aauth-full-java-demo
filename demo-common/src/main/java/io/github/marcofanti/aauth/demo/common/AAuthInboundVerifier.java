package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.demo.a2a.A2aJson;
import io.github.marcofanti.aauth.metadata.Metadata;
import io.github.marcofanti.aauth.resource.ChallengeBuilder;
import io.github.marcofanti.aauth.resource.RequestVerifier;
import io.github.marcofanti.aauth.signing.JwksFetcher;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.SignatureKeyHeader;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import java.net.URI;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;

/**
 * Inbound AAuth verification for an agent's A2A endpoint, at one of three requirement levels:
 *
 * <ul>
 *   <li>{@link Requirement#PSEUDONYM} — any valid RFC 9421 signature ({@code hwk});
 *   <li>{@link Requirement#IDENTITY} — a valid {@code aa-agent+jwt} whose issuer keys resolve
 *       via the {@link JwksFetcher};
 *   <li>{@link Requirement#AUTH_TOKEN} — an {@code aa-auth+jwt} carrying scopes; identified
 *       callers without one get a challenge embedding a freshly issued resource token
 *       ({@code aud} = the Person Server) to exchange.
 * </ul>
 *
 * <p>The target URI is rebuilt from the configured canonical base (SPEC §10.3.1) — never from
 * the request's Host header. In the full architecture the agentgateway does this verification;
 * this in-process verifier makes the signed modes self-contained until the gateway lands.
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

    /** Pseudonym-level verifier (signature required, no identity). */
    public AAuthInboundVerifier(URI canonicalBase, KeyPair resourceKeyPair, String resourceKid) {
        this(canonicalBase, resourceKeyPair, resourceKid, Requirement.PSEUDONYM, null, null, null);
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
            URI canonicalBase,
            String mode,
            String scope,
            String personServerUrl,
            KeyPair resourceKeyPair,
            String resourceKid) {
        Requirement requirement =
                switch (mode) {
                    case "auth-token", "consent" -> Requirement.AUTH_TOKEN;
                    case "jwt" -> Requirement.IDENTITY;
                    default -> Requirement.PSEUDONYM;
                };
        String effectiveScope = "consent".equals(mode) ? scope + " require:user" : scope;
        io.github.marcofanti.aauth.signing.JwksFetcher fetcher = null;
        if (requirement != Requirement.PSEUDONYM) {
            io.github.marcofanti.aauth.keys.CachingJwksFetcher caching =
                    new io.github.marcofanti.aauth.keys.CachingJwksFetcher();
            fetcher = (id, dwk, kid) -> caching.fetch(id, kid, dwk);
        }
        return new AAuthInboundVerifier(
                canonicalBase, resourceKeyPair, resourceKid, requirement, fetcher, effectiveScope, personServerUrl);
    }

    /**
     * @param requiredScope scope for issued resource tokens (append {@code require:user} to
     *     trigger the Person Server's consent flow); required for {@link Requirement#AUTH_TOKEN}
     * @param authServer the Person Server origin — the {@code aud} of issued resource tokens;
     *     required for {@link Requirement#AUTH_TOKEN}
     */
    public AAuthInboundVerifier(
            URI canonicalBase,
            KeyPair resourceKeyPair,
            String resourceKid,
            Requirement requirement,
            JwksFetcher jwksFetcher,
            String requiredScope,
            String authServer) {
        this.canonicalBase = canonicalBase;
        this.requirement = requirement;
        this.resourceKeyPair = resourceKeyPair;
        this.resourceKid = resourceKid;
        this.requiredScope = requiredScope;
        this.verifier = new RequestVerifier(List.of(canonicalBase.getRawAuthority()), jwksFetcher);
        this.resourceId = trimTrailingSlash(canonicalBase.toString());
        this.challenges = new ChallengeBuilder(resourceId, resourceKeyPair.getPrivate(), resourceKid, authServer);
    }

    /** Verification outcome. */
    public record Verification(Status status, String agentId, List<String> scopes, String error) {

        public enum Status {
            VALID,
            INVALID,
            NEEDS_AUTH_TOKEN
        }

        public boolean valid() {
            return status == Status.VALID;
        }
    }

    public Verification verify(String method, String path, Map<String, String> headers, byte[] body) {
        String effectivePath = path == null || path.isEmpty() ? "/" : path;
        String targetUri = canonicalBase.resolve(effectivePath).toString();
        boolean requireIdentity = requirement != Requirement.PSEUDONYM;
        // Since aauth-java-library 0.1.1 the RequestVerifier enforces the RFC 9530
        // body-vs-digest check itself; no demo-side re-verification needed.
        RequestVerifier.Result result =
                verifier.verifyRequest(method, targetUri, headers, body, requireIdentity, false);
        if (!result.valid()) {
            return new Verification(Verification.Status.INVALID, null, null, result.error());
        }
        if (requirement == Requirement.AUTH_TOKEN
                && (result.scopes() == null || result.scopes().isEmpty())) {
            return new Verification(
                    Verification.Status.NEEDS_AUTH_TOKEN, result.agentId(), null, "Auth token required");
        }
        return new Verification(Verification.Status.VALID, result.agentId(), result.scopes(), null);
    }

    /** Challenge for callers that failed signature/identity verification. */
    public ChallengeBuilder.Challenge challenge() {
        return challenges.buildChallenge(
                requirement == Requirement.PSEUDONYM
                        ? ChallengeBuilder.Spec.pseudonym()
                        : ChallengeBuilder.Spec.identity());
    }

    /**
     * Auth-token challenge for an identified caller: issues a resource token bound to the
     * caller's signing key (extracted from the {@code Signature-Key} agent token's
     * {@code cnf.jwk}) for it to exchange at the Person Server.
     */
    public ChallengeBuilder.Challenge authTokenChallenge(Map<String, String> headers, String agentId) {
        PublicKey callerKey = callerPublicKey(headers);
        return challenges.buildChallenge(ChallengeBuilder.Spec.authToken(agentId, callerKey, requiredScope));
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

    private static PublicKey callerPublicKey(Map<String, String> headers) {
        String signatureKey = headerIgnoringCase(headers, "Signature-Key");
        if (signatureKey == null) {
            throw new IllegalStateException("Cannot build auth-token challenge without a Signature-Key header");
        }
        String token = SignatureKeyHeader.parse(signatureKey).params().get("jwt");
        if (token == null) {
            throw new IllegalStateException("Auth-token challenge requires a jwt-scheme Signature-Key");
        }
        Object cnf = Jwts.parse(token).payload().get("cnf");
        if (cnf instanceof Map<?, ?> cnfMap && cnfMap.get("jwk") instanceof Map<?, ?> jwkMap) {
            Map<String, Object> jwk = new java.util.LinkedHashMap<>();
            jwkMap.forEach((key, value) -> jwk.put(String.valueOf(key), value));
            return Jwk.toPublicKey(jwk);
        }
        throw new IllegalStateException("Caller token has no cnf.jwk to bind a resource token to");
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
