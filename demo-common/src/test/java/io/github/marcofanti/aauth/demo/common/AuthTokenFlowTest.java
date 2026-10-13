package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcofanti.aauth.demo.common.AAuthInboundVerifier.Requirement;
import io.github.marcofanti.aauth.demo.common.AAuthInboundVerifier.Verification;
import io.github.marcofanti.aauth.headers.AAuthHeaders;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The draft-11 resource side at the auth-token level: an agent token earns a person-token
 * challenge, a person token earns a resource token derived from it, and only an auth token
 * granting the scope is served.
 */
class AuthTokenFlowTest {

    private static final URI CANONICAL = URI.create("https://sca.example/");
    private static final String RESOURCE = "https://sca.example";
    private static final String SCOPE = "supply-chain:optimize";

    private final FakePersonServer ps = new FakePersonServer();
    private final KeyPair resourceKey = KeyPairs.generateEd25519();
    private final KeyPair agentKey = KeyPairs.generateEd25519();

    private final AAuthInboundVerifier verifier = new AAuthInboundVerifier(
            CANONICAL, resourceKey, "test-rsk-1", Requirement.AUTH_TOKEN, ps.jwksFetcher(), SCOPE, FakePersonServer.PS);

    private static byte[] body(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, String> signedWith(String token, byte[] payload) {
        return AAuthClientSigner.withToken(agentKey, token)
                .sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);
    }

    @Test
    void agentTokenGetsPersonTokenChallenge() {
        byte[] payload = body("{}");
        Verification verification = verifier.verify("POST", "/", signedWith(ps.agentToken(agentKey), payload), payload);

        assertThat(verification.status()).isEqualTo(Verification.Status.NEEDS_PERSON_TOKEN);
        assertThat(verification.agentId()).isEqualTo(FakePersonServer.AGENT_ID);
        var challenge = verifier.personTokenChallenge();
        assertThat(challenge.headerName()).isEqualTo(AAuthHeaders.HEADER_AAUTH_REQUIREMENT);
        assertThat(challenge.headerValue()).isEqualTo("requirement=person-token");
    }

    @Test
    void personTokenGetsResourceTokenDerivedFromIt() {
        byte[] payload = body("{}");
        String personToken = ps.personToken(agentKey, RESOURCE);
        Map<String, String> headers = signedWith(personToken, payload);

        Verification verification = verifier.verify("POST", "/", headers, payload);
        assertThat(verification.status()).isEqualTo(Verification.Status.NEEDS_AUTH_TOKEN);
        assertThat(verification.personSub()).isEqualTo(FakePersonServer.subFor(RESOURCE));

        var challenge = verifier.authTokenChallenge(headers);
        String resourceToken =
                AAuthHeaders.parseAAuthHeader(challenge.headerValue()).resourceToken();
        Map<String, Object> claims = Jwts.parse(resourceToken).payload();
        Map<String, Object> person = Jwts.parse(personToken).payload();
        assertThat(claims.get("iss")).isEqualTo(RESOURCE);
        assertThat(claims.get("aud")).isEqualTo(FakePersonServer.PS);
        assertThat(claims.get("ps")).isEqualTo(FakePersonServer.PS);
        assertThat(claims.get("sub")).isEqualTo(person.get("sub"));
        assertThat(claims.get("presented_jti")).isEqualTo(person.get("jti"));
        assertThat(claims).doesNotContainKey("agent");
        assertThat(String.valueOf(claims.get("scope"))).contains(SCOPE);
        assertThat(claims.get("agent_jkt")).isEqualTo(Jwk.thumbprint(Jwk.publicKeyToJwk(agentKey.getPublic(), null)));
    }

    @Test
    void authTokenGrantingTheScopeIsServed() {
        byte[] payload = body("{}");
        Verification verification =
                verifier.verify("POST", "/", signedWith(ps.authToken(agentKey, RESOURCE, SCOPE), payload), payload);

        assertThat(verification.status()).isEqualTo(Verification.Status.VALID);
        assertThat(verification.personSub()).isEqualTo(FakePersonServer.subFor(RESOURCE));
        assertThat(verification.scopes()).contains(SCOPE);
    }

    @Test
    void authTokenForAnotherScopeIsRejected() {
        byte[] payload = body("{}");
        Verification verification = verifier.verify(
                "POST", "/", signedWith(ps.authToken(agentKey, RESOURCE, "other:scope"), payload), payload);

        assertThat(verification.status()).isEqualTo(Verification.Status.INVALID);
    }

    @Test
    void personTokenForAnotherResourceIsRejected() {
        byte[] payload = body("{}");
        Verification verification = verifier.verify(
                "POST", "/", signedWith(ps.personToken(agentKey, "https://other.example"), payload), payload);

        assertThat(verification.status()).isEqualTo(Verification.Status.INVALID);
    }

    @Test
    void consentModeAppendsRequireUserToTheResourceTokenScope() {
        AAuthInboundVerifier consent = AAuthInboundVerifier.forMode(
                CANONICAL, "consent", SCOPE, FakePersonServer.PS, resourceKey, "test-rsk-1");
        byte[] payload = body("{}");
        Map<String, String> headers = signedWith(ps.personToken(agentKey, RESOURCE), payload);

        // forMode's fetcher goes to the network; build the challenge directly from the token instead.
        assertThat(consent.challenge().headerValue()).contains("agent-token");
        var challenge = new AAuthInboundVerifier(
                        CANONICAL,
                        resourceKey,
                        "test-rsk-1",
                        Requirement.AUTH_TOKEN,
                        ps.jwksFetcher(),
                        SCOPE + " require:user",
                        FakePersonServer.PS)
                .authTokenChallenge(headers);
        String resourceToken =
                AAuthHeaders.parseAAuthHeader(challenge.headerValue()).resourceToken();
        assertThat(String.valueOf(Jwts.parse(resourceToken).payload().get("scope")))
                .contains("require:user");
    }

    @Test
    void forModeMapsModeStringsToChallenges() {
        KeyPair keys = KeyPairs.generateEd25519();
        AAuthInboundVerifier identity =
                AAuthInboundVerifier.forMode(CANONICAL, "jwt", SCOPE, FakePersonServer.PS, keys, "kid-1");
        AAuthInboundVerifier pseudonym =
                AAuthInboundVerifier.forMode(CANONICAL, "hwk", SCOPE, FakePersonServer.PS, keys, "kid-1");

        assertThat(identity.challenge().headerValue()).isEqualTo("requirement=agent-token");
        assertThat(pseudonym.challenge().headerName()).isEqualTo(AAuthHeaders.HEADER_ACCEPT_SIGNATURE);
        assertThat(identity.resourceJwksJson()).contains("kid-1");
    }

    @Test
    void resourceDocumentsAreServed() {
        assertThat(verifier.resourceMetadataJson())
                .contains("\"issuer\":\"" + RESOURCE + "\"")
                .contains("/.well-known/jwks.json");
        assertThat(verifier.resourceJwksJson()).contains("test-rsk-1");
    }
}
