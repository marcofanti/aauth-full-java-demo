package io.github.marcofanti.aauth.demo.a2a;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Failure of an A2A call. For HTTP-level failures the status code, response headers and body are
 * preserved so callers can inspect AAuth challenges (e.g. {@code AAuth-Requirement} on a 401).
 */
public class A2aClientException extends Exception {

    private final int statusCode;
    private final transient Map<String, List<String>> responseHeaders;
    private final String responseBody;

    public A2aClientException(String message) {
        this(message, null);
    }

    public A2aClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseHeaders = Map.of();
        this.responseBody = null;
    }

    public A2aClientException(URI target, int statusCode, Map<String, List<String>> responseHeaders, String body) {
        super("A2A call to " + target + " returned HTTP " + statusCode);
        this.statusCode = statusCode;
        this.responseHeaders = responseHeaders;
        this.responseBody = body;
    }

    /** HTTP status of the failed response, or -1 when the failure was not HTTP-level. */
    public int statusCode() {
        return statusCode;
    }

    public Map<String, List<String>> responseHeaders() {
        return responseHeaders;
    }

    public String responseBody() {
        return responseBody;
    }

    /** Case-insensitive lookup of a response header, e.g. {@code AAuth-Requirement} on a 401. */
    public Optional<String> firstResponseHeader(String name) {
        return responseHeaders.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }
}
