package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.signing.SignRequest;
import io.github.marcofanti.aauth.signing.SignatureScheme;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.net.URI;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AAuth signer behind the demo's {@link RequestSigner} seam: RFC 9421 HTTP Message Signatures
 * covering {@code @method @authority @path signature-key content-digest content-type}.
 *
 * <p>Two flavors: {@link #ephemeral()} signs pseudonymously ({@code hwk}, public key embedded
 * in {@code Signature-Key}); {@link #withAgentToken} signs with identity ({@code jwt}, carrying
 * the {@code aa-agent+jwt} whose {@code cnf.jwk} binds the same ephemeral key).
 */
public final class AAuthClientSigner implements io.github.marcofanti.aauth.demo.a2a.RequestSigner {

    private final KeyPair keyPair;
    private final Supplier<SignatureScheme> scheme;

    private AAuthClientSigner(KeyPair keyPair, Supplier<SignatureScheme> scheme) {
        this.keyPair = keyPair;
        this.scheme = scheme;
    }

    /** Pseudonymous signer with a fresh per-process Ed25519 key pair. */
    public static AAuthClientSigner ephemeral() {
        return new AAuthClientSigner(KeyPairs.generateEd25519(), SignatureScheme.Hwk::new);
    }

    /**
     * Identity signer: the ephemeral key from bootstrap plus a token bound to it via
     * {@code cnf.jwk} — either the {@code aa-agent+jwt} or an exchanged {@code aa-auth+jwt}.
     */
    public static AAuthClientSigner withToken(KeyPair ephemeralKeyPair, String token) {
        return new AAuthClientSigner(ephemeralKeyPair, () -> new SignatureScheme.Jwt(token));
    }

    @Override
    public Map<String, String> sign(String method, URI target, Map<String, String> headers, byte[] body) {
        Map<String, String> signatureHeaders =
                io.github.marcofanti.aauth.signing.RequestSigner.sign(SignRequest.builder(method, target.toString())
                        .headers(headers)
                        .body(body)
                        .keyPair(keyPair)
                        .scheme(scheme.get())
                        .additionalComponents(List.of("content-digest", "content-type"))
                        .build());
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putAll(signatureHeaders);
        return merged;
    }
}
