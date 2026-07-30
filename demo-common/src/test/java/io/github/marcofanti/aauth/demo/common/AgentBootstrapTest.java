package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentBootstrapTest {

    @TempDir
    Path keyDir;

    private HttpServer server;
    private final KeyPair providerKey = KeyPairs.generateEd25519();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private AgentBootstrap.Config config(String name) {
        return new AgentBootstrap.Config(base(), keyDir, name, Duration.ofSeconds(10));
    }

    private void serveMetadata() {
        respond(
                "/.well-known/aauth-agent.json",
                exchange -> json(exchange, 200, Map.of(), """
                {"issuer":"%s","registration_endpoint":"%s/register","refresh_endpoint":"%s/refresh"}
                """.formatted(base(), base(), base())));
    }

    private String agentTokenFor(String agentId) {
        return Jwts.signEdDsa(
                Map.of("alg", "EdDSA", "typ", "aa-agent+jwt", "kid", "as-test-1"),
                Map.of(
                        "iss",
                        base().toString(),
                        "sub",
                        agentId,
                        "cnf",
                        Map.of("jwk", Jwk.publicKeyToJwk(providerKey.getPublic(), null)),
                        "exp",
                        Instant.now().getEpochSecond() + 3600),
                providerKey.getPrivate());
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void respond(String path, Handler handler) {
        server.createContext(path, exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
    }

    private static void json(HttpExchange exchange, int status, Map<String, String> headers, String body)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.strip().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void preTrustedRegistrationReturnsIdentityImmediately() {
        serveMetadata();
        AtomicReference<Map<String, String>> registerHeaders = new AtomicReference<>();
        respond("/register", exchange -> {
            Map<String, String> captured = new java.util.LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> captured.put(name, values.get(0)));
            registerHeaders.set(captured);
            json(exchange, 200, Map.of(), "{\"agent_token\":\"" + agentTokenFor("aauth:backend@test") + "\"}");
        });

        AgentBootstrap.Identity identity = AgentBootstrap.register(config("backend"));

        assertThat(identity.agentId()).isEqualTo("aauth:backend@test");
        assertThat(identity.agentToken()).isNotBlank();
        assertThat(identity.ephemeralKeyPair()).isNotNull();
        assertThat(registerHeaders.get().keySet())
                .anySatisfy(name -> assertThat(name).isEqualToIgnoringCase("Signature-Key"));
        assertThat(headerIgnoringCase(registerHeaders.get(), "Signature-Key")).contains("hwk");
    }

    @Test
    void pendingRegistrationPollsUntilApproved() {
        serveMetadata();
        respond(
                "/register",
                exchange -> json(
                        exchange,
                        202,
                        Map.of("Location", "/register/pending/p1", "Retry-After", "0"),
                        "{\"status\":\"pending\"}"));
        AtomicInteger polls = new AtomicInteger();
        respond("/register/pending/p1", exchange -> {
            if (polls.incrementAndGet() < 3) {
                json(exchange, 202, Map.of(), "{\"status\":\"pending\"}");
            } else {
                json(exchange, 200, Map.of(), "{\"agent_token\":\"" + agentTokenFor("aauth:sca@test") + "\"}");
            }
        });

        AgentBootstrap.Identity identity = AgentBootstrap.register(config("supply-chain-agent"));

        assertThat(identity.agentId()).isEqualTo("aauth:sca@test");
        assertThat(polls.get()).isEqualTo(3);
    }

    @Test
    void deniedRegistrationFails() {
        serveMetadata();
        respond(
                "/register",
                exchange -> json(
                        exchange,
                        202,
                        Map.of("Location", "/register/pending/p2", "Retry-After", "0"),
                        "{\"status\":\"pending\"}"));
        respond("/register/pending/p2", exchange -> json(exchange, 403, Map.of(), "{\"error\":\"denied\"}"));

        assertThatExceptionOfType(BootstrapException.class)
                .isThrownBy(() -> AgentBootstrap.register(config("denied-agent")))
                .withMessageContaining("denied");
    }

    @Test
    void registrationErrorFailsFast() {
        serveMetadata();
        respond("/register", exchange -> json(exchange, 400, Map.of(), "{\"error\":\"bad_request\"}"));

        assertThatExceptionOfType(BootstrapException.class)
                .isThrownBy(() -> AgentBootstrap.register(config("broken-agent")))
                .withMessageContaining("HTTP 400");
    }

    @Test
    void unreachableRegistrationEndpointFails() {
        respond("/.well-known/aauth-agent.json", exchange -> json(exchange, 200, Map.of(), """
                {"issuer":"%s","registration_endpoint":"http://127.0.0.1:1/register"}
                """.formatted(base())));

        assertThatExceptionOfType(BootstrapException.class)
                .isThrownBy(() -> AgentBootstrap.register(config("island-agent")))
                .withMessageContaining("failed");
    }

    @Test
    void missingAgentTokenInResponseFails() {
        serveMetadata();
        respond("/register", exchange -> json(exchange, 200, Map.of(), "{\"status\":\"weird\"}"));

        assertThatExceptionOfType(BootstrapException.class)
                .isThrownBy(() -> AgentBootstrap.register(config("tokenless-agent")))
                .withMessageContaining("no agent_token");
    }

    @Test
    void malformedJsonResponseFails() {
        serveMetadata();
        respond("/register", exchange -> json(exchange, 200, Map.of(), "not json"));

        assertThatExceptionOfType(BootstrapException.class)
                .isThrownBy(() -> AgentBootstrap.register(config("garbled-agent")))
                .withMessageContaining("Malformed JSON");
    }

    @Test
    void acceptedWithoutLocationFails() {
        serveMetadata();
        respond("/register", exchange -> json(exchange, 202, Map.of(), "{\"status\":\"pending\"}"));

        assertThatExceptionOfType(BootstrapException.class)
                .isThrownBy(() -> AgentBootstrap.register(config("lost-agent")))
                .withMessageContaining("Location");
    }

    private static String headerIgnoringCase(Map<String, String> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
