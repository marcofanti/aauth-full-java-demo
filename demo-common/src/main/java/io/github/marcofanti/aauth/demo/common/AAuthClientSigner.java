package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.signing.SignRequest;
import io.github.marcofanti.aauth.signing.SignatureScheme;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.net.URI;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AAuth HWK signer behind the demo's {@link RequestSigner} seam: RFC 9421 HTTP Message
 * Signatures with the Ed25519 public key embedded in {@code Signature-Key} (pseudonymous).
 * The signature covers {@code @method @authority @path signature-key content-digest
 * content-type}, so the body bytes are tamper-evident.
 *
 * <p>The key pair is ephemeral per process, mirroring the reference demo's ephemeral signing
 * keys. The {@code jwt} scheme (agent/auth tokens from the Person Server) replaces this in
 * demo phases 4–6.
 */
public final class AAuthClientSigner implements io.github.marcofanti.aauth.demo.a2a.RequestSigner {

    private final KeyPair keyPair;

    public AAuthClientSigner(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    /** New signer with a fresh per-process Ed25519 key pair. */
    public static AAuthClientSigner ephemeral() {
        return new AAuthClientSigner(KeyPairs.generateEd25519());
    }

    @Override
    public Map<String, String> sign(String method, URI target, Map<String, String> headers, byte[] body) {
        Map<String, String> signatureHeaders =
                io.github.marcofanti.aauth.signing.RequestSigner.sign(SignRequest.builder(method, target.toString())
                        .headers(headers)
                        .body(body)
                        .keyPair(keyPair)
                        .scheme(new SignatureScheme.Hwk())
                        .additionalComponents(List.of("content-digest", "content-type"))
                        .build());
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putAll(signatureHeaders);
        return merged;
    }
}
