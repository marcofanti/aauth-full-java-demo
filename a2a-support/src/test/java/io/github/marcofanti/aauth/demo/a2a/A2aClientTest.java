package io.github.marcofanti.aauth.demo.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class A2aClientTest {

    private HttpServer server;
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI serverUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void respondWith(int status, Map<String, String> headers, String body) {
        server.createContext("/", exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            Map<String, String> captured = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> captured.put(name, values.get(0)));
            receivedHeaders.set(captured);
            headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, responseBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBytes);
            }
        });
    }

    private static String firstHeaderIgnoringCase(Map<String, String> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    @Test
    void sendsSignedBytesUnmodifiedAndReturnsReplyText() throws Exception {
        String reply = A2aJson.toJson(JsonRpcResponse.success("any", A2aMessage.agentText("m", "the report")));
        respondWith(200, Map.of("Content-Type", "application/json"), reply);

        AtomicReference<byte[]> signedBody = new AtomicReference<>();
        RequestSigner signer = (method, target, headers, body) -> {
            signedBody.set(body);
            Map<String, String> signed = new LinkedHashMap<>(headers);
            signed.put("X-Demo-Signature", "stub");
            return signed;
        };

        String result = new A2aClient(signer).sendText(serverUri(), "optimize");

        assertThat(result).isEqualTo("the report");
        assertThat(receivedBody.get()).isEqualTo(signedBody.get());
        assertThat(firstHeaderIgnoringCase(receivedHeaders.get(), "x-demo-signature"))
                .isEqualTo("stub");
        JsonRpcRequest sent =
                A2aJson.parse(new String(receivedBody.get(), StandardCharsets.UTF_8), JsonRpcRequest.class);
        assertThat(sent.method()).isEqualTo(JsonRpcRequest.MESSAGE_SEND);
        assertThat(sent.params().message().text()).isEqualTo("optimize");
    }

    @Test
    void httpErrorPreservesStatusHeadersAndBody() {
        respondWith(401, Map.of("AAuth-Requirement", "requirement=auth-token"), "{\"error\":\"missing_signature\"}");

        assertThatExceptionOfType(A2aClientException.class)
                .isThrownBy(() -> new A2aClient(RequestSigner.none()).sendText(serverUri(), "optimize"))
                .satisfies(e -> {
                    assertThat(e.statusCode()).isEqualTo(401);
                    assertThat(e.firstResponseHeader("aauth-requirement")).contains("requirement=auth-token");
                    assertThat(e.responseBody()).contains("missing_signature");
                });
    }

    @Test
    void jsonRpcErrorBecomesException() {
        String reply = A2aJson.toJson(JsonRpcResponse.failure("any", JsonRpcError.INTERNAL_ERROR, "boom"));
        respondWith(200, Map.of(), reply);

        assertThatExceptionOfType(A2aClientException.class)
                .isThrownBy(() -> new A2aClient(RequestSigner.none()).sendText(serverUri(), "optimize"))
                .withMessageContaining("boom")
                .satisfies(e -> assertThat(e.statusCode()).isEqualTo(-1));
    }

    @Test
    void connectionFailureIsWrapped() {
        assertThatExceptionOfType(A2aClientException.class)
                .isThrownBy(() ->
                        new A2aClient(RequestSigner.none()).sendText(URI.create("http://127.0.0.1:1"), "optimize"))
                .withMessageContaining("failed");
    }

    @Test
    void emptyPathIsNormalizedToRoot() throws Exception {
        String reply = A2aJson.toJson(JsonRpcResponse.success("any", A2aMessage.agentText("m", "ok")));
        respondWith(200, Map.of(), reply);
        AtomicReference<URI> signedTarget = new AtomicReference<>();
        RequestSigner signer = (method, target, headers, body) -> {
            signedTarget.set(target);
            return headers;
        };

        new A2aClient(signer).sendText(serverUri(), "hello");

        assertThat(signedTarget.get().getPath()).isEqualTo("/");
    }
}
