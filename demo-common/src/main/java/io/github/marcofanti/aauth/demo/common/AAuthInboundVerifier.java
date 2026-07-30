package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.resource.ChallengeBuilder;
import io.github.marcofanti.aauth.resource.RequestVerifier;
import io.github.marcofanti.aauth.signing.SignatureBase;
import java.net.URI;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;

/**
 * Inbound AAuth verification for an agent's A2A endpoint.
 *
 * <p>The target URI is rebuilt from the configured canonical base (SPEC §10.3.1) — never from
 * the request's Host header. In the full architecture the agentgateway does this verification;
 * this in-process verifier makes the signed mode self-contained until the gateway lands
 * (demo phase 2).
 */
public final class AAuthInboundVerifier {

    private final RequestVerifier verifier;
    private final URI canonicalBase;
    private final ChallengeBuilder challenges;

    public AAuthInboundVerifier(URI canonicalBase, KeyPair resourceKeyPair, String resourceKid) {
        this.canonicalBase = canonicalBase;
        this.verifier = new RequestVerifier(List.of(canonicalBase.getRawAuthority()), null);
        String resourceId = canonicalBase.toString().endsWith("/")
                ? canonicalBase.toString().substring(0, canonicalBase.toString().length() - 1)
                : canonicalBase.toString();
        this.challenges = new ChallengeBuilder(resourceId, resourceKeyPair.getPrivate(), resourceKid, null);
    }

    /** Failed verification outcome; {@code error} is null when {@code valid}. */
    public record Verification(boolean valid, String error) {}

    public Verification verify(String method, String path, Map<String, String> headers, byte[] body) {
        String effectivePath = path == null || path.isEmpty() ? "/" : path;
        String targetUri = canonicalBase.resolve(effectivePath).toString();
        RequestVerifier.Result result = verifier.verifyRequest(method, targetUri, headers, body, false, false);
        if (!result.valid()) {
            return new Verification(false, result.error());
        }
        // The library (matching the Python reference) signs over the Content-Digest *header*
        // but leaves RFC 9530 body-vs-digest enforcement to the resource. Do it here.
        String declaredDigest = headerIgnoringCase(headers, "Content-Digest");
        if (declaredDigest != null && body != null && !declaredDigest.equals(SignatureBase.contentDigest(body))) {
            return new Verification(false, "Content-Digest does not match request body");
        }
        return new Verification(true, null);
    }

    private static String headerIgnoringCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Pseudonym-level challenge (header name + value) for 401 responses. */
    public ChallengeBuilder.Challenge challenge() {
        return challenges.buildChallenge(ChallengeBuilder.Spec.pseudonym());
    }
}
