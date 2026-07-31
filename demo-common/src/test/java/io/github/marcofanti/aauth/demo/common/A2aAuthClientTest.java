package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.marcofanti.aauth.demo.a2a.A2aClientException;
import io.github.marcofanti.aauth.demo.a2a.A2aJson;
import io.github.marcofanti.aauth.demo.a2a.A2aMessage;
import io.github.marcofanti.aauth.demo.a2a.JsonRpcResponse;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.SignatureKeyHeader;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Full three-party exchange against stub servers: the resource 401s with a resource token
 * until an auth token is presented; the stub Person Server exchanges it.
 */
class A2aAuthClientTest {

    private HttpServer resource;
    private HttpServer personServer;
    private final KeyPair providerKey = KeyPairs.generateEd25519();
    private final KeyPair ephemeralKey = KeyPairs.generateEd25519();
    private final KeyPair resourceKey = KeyPairs.generateEd25519();

    @BeforeEach
    void startServers() throws IOException {
        resource = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resource.start();
        personServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        personServer.start();
    }

    @AfterEach
    void stopServers() {
        resource.stop(0);
        personServer.stop(0);
    }

    private URI resourceUri() {
        return URI.create("http://127.0.0.1:" + resource.getAddress().getPort());
    }

    private String psUrl() {
        return "http://127.0.0.1:" + personServer.getAddress().getPort();
    }

    private AgentBootstrap.Identity identity() {
        String agentToken = Jwts.signEdDsa(
                Map.of("alg", "EdDSA", "typ", "aa-agent+jwt", "kid", "ps-1"),
                Map.of(
                        "iss",
                        psUrl(),
                        "sub",
                        "aauth:client@test",
                        "cnf",
                        Map.of("jwk", Jwk.publicKeyToJwk(ephemeralKey.getPublic(), null)),
                        "exp",
                        Instant.now().getEpochSecond() + 3600),
                providerKey.getPrivate());
        return new AgentBootstrap.Identity("aauth:client@test", agentToken, ephemeralKey);
    }

    private String resourceToken() {
        return Jwts.signEdDsa(
                Map.of("alg", "EdDSA", "typ", "aa-resource+jwt", "kid", "rsk-1"),
                Map.of(
                        "iss",
                        resourceUri().toString(),
                        "aud",
                        psUrl(),
                        "agent",
                        "aauth:client@test",
                        "agent_jkt",
                        Jwk.thumbprint(Jwk.publicKeyToJwk(ephemeralKey.getPublic(), null)),
                        "scope",
                        "test:scope",
                        "exp",
                        Instant.now().getEpochSecond() + 300),
                resourceKey.getPrivate());
    }

    private String authToken() {
        return Jwts.signEdDsa(
                Map.of("alg", "EdDSA", "typ", "aa-auth+jwt", "kid", "ps-1"),
                Map.of(
                        "iss",
                        psUrl(),
                        "scope",
                        "test:scope",
                        "exp",
                        Instant.now().getEpochSecond() + 600),
                providerKey.getPrivate());
    }

    private static void respond(HttpExchange exchange, int status, Map<String, String> headers, String body)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void stubPersonServer() {
        personServer.createContext("/.well-known/aauth-person.json", exchange -> {
            try (exchange) {
                respond(
                        exchange,
                        200,
                        Map.of("Content-Type", "application/json"),
                        "{\"issuer\":\"" + psUrl() + "\",\"token_endpoint\":\"" + psUrl() + "/token\"}");
            }
        });
        personServer.createContext("/token", exchange -> {
            try (exchange) {
                respond(
                        exchange,
                        200,
                        Map.of("Content-Type", "application/json"),
                        "{\"auth_token\":\"" + authToken() + "\",\"expires_in\":600}");
            }
        });
    }

    /** 401 + resource token while the caller presents an agent token; 200 with an auth token. */
    private AtomicInteger stubResourceRequiringAuthToken() {
        AtomicInteger calls = new AtomicInteger();
        resource.createContext("/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                String signatureKey = exchange.getRequestHeaders().getFirst("Signature-Key");
                String jwt = SignatureKeyHeader.parse(signatureKey).params().get("jwt");
                Object typ = Jwts.parse(jwt).header().get("typ");
                if ("aa-auth+jwt".equals(typ)) {
                    String reply = A2aJson.toJson(
                            JsonRpcResponse.success("1", A2aMessage.agentText("m", "authorized result")));
                    respond(exchange, 200, Map.of("Content-Type", "application/json"), reply);
                } else {
                    respond(
                            exchange,
                            401,
                            Map.of(
                                    "AAuth-Requirement",
                                    "requirement=auth-token, resource-token=\"" + resourceToken() + "\""),
                            "{\"error\":\"auth_token_required\"}");
                }
            }
        });
        return calls;
    }

    @Test
    void exchangesResourceTokenAndRetries() throws Exception {
        stubPersonServer();
        AtomicInteger calls = stubResourceRequiringAuthToken();
        AgentBootstrap.Identity fixed = identity();
        A2aAuthClient client = new A2aAuthClient(resourceUri(), () -> fixed);

        String reply = client.sendText("do the thing", null);

        assertThat(reply).isEqualTo("authorized result");
        assertThat(calls.get()).isEqualTo(2);

        // The auth token is cached: the next call succeeds on the first attempt.
        assertThat(client.sendText("again", null)).isEqualTo("authorized result");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void identityRotationDropsCachedAuthToken() throws Exception {
        stubPersonServer();
        AtomicInteger calls = stubResourceRequiringAuthToken();
        java.util.concurrent.atomic.AtomicReference<AgentBootstrap.Identity> active =
                new java.util.concurrent.atomic.AtomicReference<>(identity());
        A2aAuthClient client = new A2aAuthClient(resourceUri(), active::get);

        client.sendText("first", null);
        assertThat(calls.get()).isEqualTo(2); // 401 + retried with auth token

        active.set(identity()); // refresh: new Identity instance, same agent
        client.sendText("second", null);
        // Cache dropped: agent-token attempt (401) + fresh exchange + retry.
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void non401FailuresPassThrough() {
        resource.createContext("/", exchange -> {
            try (exchange) {
                respond(exchange, 500, Map.of(), "{\"error\":\"boom\"}");
            }
        });

        assertThatExceptionOfType(A2aClientException.class)
                .isThrownBy(() -> {
                    AgentBootstrap.Identity fixed = identity();
                    new A2aAuthClient(resourceUri(), () -> fixed).sendText("hi", null);
                })
                .satisfies(e -> assertThat(e.statusCode()).isEqualTo(500));
    }

    @Test
    void unauthorizedWithoutResourceTokenPassesThrough() {
        resource.createContext("/", exchange -> {
            try (exchange) {
                respond(exchange, 401, Map.of(), "{\"error\":\"nope\"}");
            }
        });

        assertThatExceptionOfType(A2aClientException.class)
                .isThrownBy(() -> {
                    AgentBootstrap.Identity fixed = identity();
                    new A2aAuthClient(resourceUri(), () -> fixed).sendText("hi", null);
                })
                .satisfies(e -> assertThat(e.statusCode()).isEqualTo(401));
    }
}
