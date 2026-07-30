package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcofanti.aauth.demo.common.AAuthInboundVerifier.Requirement;
import io.github.marcofanti.aauth.demo.common.AAuthInboundVerifier.Verification;
import io.github.marcofanti.aauth.signing.JwksFetcher;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The mode-3 resource side: identified callers without scopes get a resource-token challenge. */
class AuthTokenFlowTest {

    private static final URI CANONICAL = URI.create("http://gateway.uma.lab:9999/");
    private static final String PS_ISSUER = "http://ps.uma.lab:8765";
    private static final String AGENT_ID = "aauth:backend@uma.lab";
    private static final String SCOPE = "supply-chain:optimize";

    private final KeyPair providerKey = KeyPairs.generateEd25519();
    private final KeyPair ephemeralKey = KeyPairs.generateEd25519();

    private final JwksFetcher providerJwks =
            (id, dwk, kid) -> Jwk.generateJwks(List.of(Jwk.publicKeyToJwk(providerKey.getPublic(), "ps-test-1")));

    private final AAuthInboundVerifier verifier = new AAuthInboundVerifier(
            CANONICAL,
            KeyPairs.generateEd25519(),
            "test-rsk-1",
            Requirement.AUTH_TOKEN,
            providerJwks,
            SCOPE,
            PS_ISSUER);

    private String agentToken() {
        return Jwts.signEdDsa(
                Map.of("alg", "EdDSA", "typ", "aa-agent+jwt", "kid", "ps-test-1"),
                Map.of(
                        "iss",
                        PS_ISSUER,
                        "sub",
                        AGENT_ID,
                        "cnf",
                        Map.of("jwk", Jwk.publicKeyToJwk(ephemeralKey.getPublic(), null)),
                        "exp",
                        Instant.now().getEpochSecond() + 3600),
                providerKey.getPrivate());
    }

    private String authToken(String scope) {
        return Jwts.signEdDsa(
                Map.of("alg", "EdDSA", "typ", "aa-auth+jwt", "kid", "ps-test-1"),
                Map.of(
                        "iss",
                        PS_ISSUER,
                        "aud",
                        "http://gateway.uma.lab:9999",
                        "agent",
                        AGENT_ID,
                        "sub",
                        "user",
                        "act",
                        Map.of("sub", AGENT_ID),
                        "scope",
                        scope,
                        "cnf",
                        Map.of("jwk", Jwk.publicKeyToJwk(ephemeralKey.getPublic(), null)),
                        "exp",
                        Instant.now().getEpochSecond() + 600),
                providerKey.getPrivate());
    }

    private static byte[] body(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void agentTokenOnlyGetsResourceTokenChallenge() {
        AAuthClientSigner signer = AAuthClientSigner.withToken(ephemeralKey, agentToken());
        byte[] payload = body("{}");
        Map<String, String> headers =
                signer.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);

        Verification verification = verifier.verify("POST", "/", headers, payload);

        assertThat(verification.status()).isEqualTo(Verification.Status.NEEDS_AUTH_TOKEN);
        assertThat(verification.agentId()).isEqualTo(AGENT_ID);

        var challenge = verifier.authTokenChallenge(headers, verification.agentId());
        assertThat(challenge.headerName()).isEqualTo("AAuth-Requirement");
        assertThat(challenge.headerValue()).contains("auth-token").contains("resource-token=");
    }

    @Test
    void issuedResourceTokenBindsCallerAndPersonServer() {
        AAuthClientSigner signer = AAuthClientSigner.withToken(ephemeralKey, agentToken());
        byte[] payload = body("{}");
        Map<String, String> headers =
                signer.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);
        var challenge = verifier.authTokenChallenge(headers, AGENT_ID);

        String resourceToken = challenge.headerValue().replaceAll(".*resource-token=\"([^\"]+)\".*", "$1");
        Map<String, Object> claims = Jwts.parse(resourceToken).payload();

        assertThat(claims.get("iss")).isEqualTo("http://gateway.uma.lab:9999");
        assertThat(claims.get("aud")).isEqualTo(PS_ISSUER);
        assertThat(claims.get("agent")).isEqualTo(AGENT_ID);
        assertThat(String.valueOf(claims.get("scope"))).contains(SCOPE);
        assertThat(claims.get("agent_jkt"))
                .isEqualTo(Jwk.thumbprint(Jwk.publicKeyToJwk(ephemeralKey.getPublic(), null)));
    }

    @Test
    void authTokenWithScopesIsAccepted() {
        AAuthClientSigner signer = AAuthClientSigner.withToken(ephemeralKey, authToken(SCOPE));
        byte[] payload = body("{}");
        Map<String, String> headers =
                signer.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);

        Verification verification = verifier.verify("POST", "/", headers, payload);

        assertThat(verification.status()).isEqualTo(Verification.Status.VALID);
        assertThat(verification.agentId()).isEqualTo(AGENT_ID);
        assertThat(verification.scopes()).contains(SCOPE);
    }

    @Test
    void forModeMapsModeStringsToRequirements() {
        AAuthInboundVerifier consent = AAuthInboundVerifier.forMode(
                CANONICAL, "consent", SCOPE, PS_ISSUER, KeyPairs.generateEd25519(), "kid-1");
        AAuthInboundVerifier identity =
                AAuthInboundVerifier.forMode(CANONICAL, "jwt", SCOPE, PS_ISSUER, KeyPairs.generateEd25519(), "kid-1");
        AAuthInboundVerifier pseudonym =
                AAuthInboundVerifier.forMode(CANONICAL, "hwk", SCOPE, PS_ISSUER, KeyPairs.generateEd25519(), "kid-1");

        // Identity-carrying modes issue Accept-Signature challenges; all serve resource docs.
        assertThat(consent.challenge().headerName()).isEqualTo("Accept-Signature");
        assertThat(identity.challenge().headerName()).isEqualTo("Accept-Signature");
        assertThat(pseudonym.challenge().headerName()).isEqualTo("Accept-Signature");
        assertThat(consent.resourceJwksJson()).contains("kid-1");
    }

    @Test
    void resourceDocumentsAreServed() {
        assertThat(verifier.resourceMetadataJson())
                .contains("\"issuer\":\"http://gateway.uma.lab:9999\"")
                .contains("/.well-known/jwks.json");
        assertThat(verifier.resourceJwksJson())
                .contains("\"kid\":\"test-rsk-1\"")
                .contains("\"keys\"");
    }
}
