package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Signs with {@code scheme=jwt} (agent token) and verifies with identity required. */
class IdentityRoundTripTest {

    private static final URI CANONICAL = URI.create("https://sca.example/");

    private final FakePersonServer ps = new FakePersonServer();
    private final KeyPair agentKey = KeyPairs.generateEd25519();
    private final AAuthInboundVerifier verifier = new AAuthInboundVerifier(
            CANONICAL,
            KeyPairs.generateEd25519(),
            "test-rsk-1",
            AAuthInboundVerifier.Requirement.IDENTITY,
            ps.jwksFetcher(),
            "unused:scope",
            FakePersonServer.PS);

    private static byte[] body() {
        return "{\"jsonrpc\":\"2.0\"}".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void jwtSignedRequestVerifiesAndYieldsAgentIdentity() {
        byte[] payload = body();
        Map<String, String> headers = AAuthClientSigner.withToken(agentKey, ps.agentToken(agentKey))
                .sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);

        AAuthInboundVerifier.Verification verification = verifier.verify("POST", "/", headers, payload);

        assertThat(verification.error()).isNull();
        assertThat(verification.valid()).isTrue();
        assertThat(verification.agentId()).isEqualTo(FakePersonServer.AGENT_ID);
    }

    @Test
    void pseudonymousRequestIsRejectedWhenIdentityRequired() {
        byte[] payload = body();
        Map<String, String> headers = AAuthClientSigner.ephemeral()
                .sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);

        assertThat(verifier.verify("POST", "/", headers, payload).valid()).isFalse();
    }

    @Test
    void identityModeChallengeAsksForAnAgentToken() {
        assertThat(verifier.challenge().headerValue()).isEqualTo("requirement=agent-token");
    }

    @Test
    void wrongEphemeralKeyFailsProofOfPossession() {
        byte[] payload = body();
        // Token binds agentKey, but the request is signed with a different key.
        Map<String, String> headers = AAuthClientSigner.withToken(KeyPairs.generateEd25519(), ps.agentToken(agentKey))
                .sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);

        assertThat(verifier.verify("POST", "/", headers, payload).valid()).isFalse();
    }
}
