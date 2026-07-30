package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Signs with {@code scheme=jwt} (agent token) and verifies with identity required. */
class IdentityRoundTripTest {

    private static final URI CANONICAL = URI.create("http://gateway.uma.lab:9999/");
    private static final String PS_ISSUER = "http://ps.uma.lab:8765";
    private static final String AGENT_ID = "aauth:backend@uma.lab";

    private final KeyPair providerKey = KeyPairs.generateEd25519();
    private final KeyPair ephemeralKey = KeyPairs.generateEd25519();

    private final JwksFetcher providerJwks =
            (id, dwk, kid) -> Jwk.generateJwks(List.of(Jwk.publicKeyToJwk(providerKey.getPublic(), "ps-test-1")));

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

    @Test
    void jwtSignedRequestVerifiesAndYieldsAgentIdentity() {
        AAuthClientSigner signer = AAuthClientSigner.withToken(ephemeralKey, agentToken());
        AAuthInboundVerifier verifier = new AAuthInboundVerifier(
                CANONICAL,
                KeyPairs.generateEd25519(),
                "test-rsk-1",
                AAuthInboundVerifier.Requirement.IDENTITY,
                providerJwks,
                null,
                null);
        byte[] body = "{\"jsonrpc\":\"2.0\"}".getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = signer.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), body);
        AAuthInboundVerifier.Verification verification = verifier.verify("POST", "/", headers, body);

        assertThat(verification.error()).isNull();
        assertThat(verification.valid()).isTrue();
        assertThat(verification.agentId()).isEqualTo(AGENT_ID);
    }

    @Test
    void pseudonymousRequestFailsWhenIdentityRequired() {
        AAuthClientSigner hwkSigner = AAuthClientSigner.ephemeral();
        AAuthInboundVerifier verifier = new AAuthInboundVerifier(
                CANONICAL,
                KeyPairs.generateEd25519(),
                "test-rsk-1",
                AAuthInboundVerifier.Requirement.IDENTITY,
                providerJwks,
                null,
                null);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers =
                hwkSigner.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), body);
        AAuthInboundVerifier.Verification verification = verifier.verify("POST", "/", headers, body);

        assertThat(verification.valid()).isFalse();
        assertThat(verification.error()).contains("identity");
    }

    @Test
    void identityModeChallengeIsAcceptSignature() {
        AAuthInboundVerifier verifier = new AAuthInboundVerifier(
                CANONICAL,
                KeyPairs.generateEd25519(),
                "test-rsk-1",
                AAuthInboundVerifier.Requirement.IDENTITY,
                providerJwks,
                null,
                null);

        assertThat(verifier.challenge().headerName()).isEqualTo("Accept-Signature");
    }

    @Test
    void wrongEphemeralKeyFailsProofOfPossession() {
        // Token binds `ephemeralKey`, but the attacker signs with a different key.
        KeyPair attackerKey = KeyPairs.generateEd25519();
        AAuthClientSigner signer = AAuthClientSigner.withToken(attackerKey, agentToken());
        AAuthInboundVerifier verifier = new AAuthInboundVerifier(
                CANONICAL,
                KeyPairs.generateEd25519(),
                "test-rsk-1",
                AAuthInboundVerifier.Requirement.IDENTITY,
                providerJwks,
                null,
                null);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = signer.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), body);

        assertThat(verifier.verify("POST", "/", headers, body).valid()).isFalse();
    }
}
