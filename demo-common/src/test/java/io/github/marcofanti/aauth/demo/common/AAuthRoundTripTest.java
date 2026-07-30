package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AAuthRoundTripTest {

    private static final URI CANONICAL = URI.create("http://gateway.uma.lab:9999/");

    private final AAuthClientSigner signer = AAuthClientSigner.ephemeral();
    private final AAuthInboundVerifier verifier =
            new AAuthInboundVerifier(CANONICAL, KeyPairs.generateEd25519(), "test-rsk-1");

    private static byte[] body(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void signedRequestVerifies() {
        byte[] payload = body("{\"jsonrpc\":\"2.0\"}");
        Map<String, String> headers =
                signer.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);

        AAuthInboundVerifier.Verification verification = verifier.verify("POST", "/", headers, payload);

        assertThat(verification.error()).isNull();
        assertThat(verification.valid()).isTrue();
    }

    @Test
    void signerAddsSignatureAndDigestHeaders() {
        Map<String, String> headers =
                signer.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), body("{}"));

        assertThat(headers)
                .containsKeys("Signature-Input", "Signature", "Signature-Key", "Content-Digest", "Content-Type");
        assertThat(headers.get("Signature-Key")).contains("hwk");
    }

    @Test
    void tamperedBodyIsRejected() {
        byte[] payload = body("{\"amount\":100}");
        Map<String, String> headers =
                signer.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);

        AAuthInboundVerifier.Verification verification =
                verifier.verify("POST", "/", headers, body("{\"amount\":999}"));

        assertThat(verification.valid()).isFalse();
    }

    @Test
    void unsignedRequestIsRejectedWithMissingHeadersError() {
        AAuthInboundVerifier.Verification verification =
                verifier.verify("POST", "/", Map.of("Content-Type", "application/json"), body("{}"));

        assertThat(verification.valid()).isFalse();
        assertThat(verification.error()).contains("Missing signature headers");
    }

    @Test
    void wrongAuthorityIsRejected() {
        URI elsewhere = URI.create("http://portal.uma.lab:9999/");
        byte[] payload = body("{}");
        Map<String, String> headers =
                signer.sign("POST", elsewhere, Map.of("Content-Type", "application/json"), payload);
        AAuthInboundVerifier verifierForOtherHost =
                new AAuthInboundVerifier(CANONICAL, KeyPairs.generateEd25519(), "test-rsk-1");

        // Signed for portal.uma.lab but presented as if received at gateway.uma.lab.
        AAuthInboundVerifier.Verification verification = verifierForOtherHost.verify("POST", "/", headers, payload);

        assertThat(verification.valid()).isFalse();
    }

    @Test
    void challengeIsPseudonymAcceptSignature() {
        var challenge = verifier.challenge();

        assertThat(challenge.headerName()).isEqualTo("Accept-Signature");
        assertThat(challenge.headerValue()).isNotBlank();
    }

    @Test
    void emptyPathVerifiesAsRoot() {
        byte[] payload = body("{}");
        Map<String, String> headers =
                signer.sign("POST", CANONICAL, Map.of("Content-Type", "application/json"), payload);

        assertThat(verifier.verify("POST", "", headers, payload).valid()).isTrue();
    }
}
