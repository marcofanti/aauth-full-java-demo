package io.github.marcofanti.aauth.demo.a2a;

import java.net.URI;
import java.util.Map;

/**
 * Hook for decorating outbound A2A requests with authentication headers.
 *
 * <p>Implementations receive the exact bytes that will be sent on the wire and return the full
 * header map to use (typically the input headers plus {@code Signature-Input}, {@code Signature}
 * and {@code Signature-Key} once AAuth signing is wired in).
 */
@FunctionalInterface
public interface RequestSigner {

    Map<String, String> sign(String method, URI target, Map<String, String> headers, byte[] body);

    /** No-op signer for the unauthenticated "mode0" wiring. */
    static RequestSigner none() {
        return (method, target, headers, body) -> headers;
    }
}
