package io.github.marcofanti.aauth.demo.a2a;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal A2A client: non-streaming {@code message/send} over JSON-RPC.
 *
 * <p>Serializes the request once via {@link A2aJson}, hands those exact bytes to the
 * {@link RequestSigner}, and sends them unmodified — a prerequisite for RFC 9421 signing.
 */
public final class A2aClient {

    private final HttpClient http;
    private final RequestSigner signer;
    private final Duration requestTimeout;

    public A2aClient(RequestSigner signer) {
        this(signer, Duration.ofSeconds(120));
    }

    public A2aClient(RequestSigner signer, Duration requestTimeout) {
        this.http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.signer = signer;
        this.requestTimeout = requestTimeout;
    }

    /** Sends {@code text} as a user message to the agent at {@code endpoint}; returns the reply text. */
    public String sendText(URI endpoint, String text) throws A2aClientException {
        URI target = normalize(endpoint);
        A2aMessage message = A2aMessage.userText(UUID.randomUUID().toString(), text);
        JsonRpcRequest request = JsonRpcRequest.messageSend(UUID.randomUUID().toString(), message);
        byte[] body = A2aJson.toBytes(request);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        Map<String, String> signedHeaders = signer.sign("POST", target, headers, body);

        HttpResponse<String> response = send(target, signedHeaders, body);
        if (response.statusCode() != 200) {
            throw new A2aClientException(
                    target, response.statusCode(), response.headers().map(), response.body());
        }
        return extractReplyText(response.body());
    }

    private HttpResponse<String> send(URI target, Map<String, String> headers, byte[] body) throws A2aClientException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new A2aClientException("A2A call to " + target + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2aClientException("A2A call to " + target + " interrupted", e);
        }
    }

    private static String extractReplyText(String responseBody) throws A2aClientException {
        JsonRpcResponse rpc = A2aJson.parse(responseBody, JsonRpcResponse.class);
        if (rpc.error() != null) {
            throw new A2aClientException(
                    "JSON-RPC error " + rpc.error().code() + ": " + rpc.error().message());
        }
        if (rpc.result() == null) {
            throw new A2aClientException("JSON-RPC response has neither result nor error");
        }
        return rpc.result().text();
    }

    /** An empty path must sign and send as "/" — see AAuth demo gotcha #2. */
    private static URI normalize(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isEmpty()) {
            return endpoint.resolve("/");
        }
        return endpoint;
    }
}
