package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.marcofanti.aauth.demo.a2a.A2aJson;
import io.github.marcofanti.aauth.missions.Missions;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Mission-layer flows against a stub Person Server: propose, permission, audit, deferral. */
class MissionClientTest {

    /** A draft-11 approval body ({@code s256} + base64url mission blob), as the PS returns it. */
    private static final Map<String, Object> APPROVAL =
            Missions.buildApproval(Map.of("description", "Optimize things", "agent", "aauth:client@test"), null, null);

    private static final String S256 = String.valueOf(APPROVAL.get("s256"));
    private static final String APPROVAL_JSON = A2aJson.toJson(APPROVAL);

    private HttpServer personServer;
    private final KeyPair providerKey = KeyPairs.generateEd25519();
    private final KeyPair ephemeralKey = KeyPairs.generateEd25519();

    @BeforeEach
    void startServer() throws IOException {
        personServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        personServer.start();
        personServer.createContext("/.well-known/aauth-person.json", exchange -> {
            try (exchange) {
                String base = baseUri().toString();
                respond(
                        exchange,
                        200,
                        Map.of("Content-Type", "application/json"),
                        A2aJson.toJson(Map.of(
                                "issuer", base,
                                "mission_endpoint", base + "/agent-missions",
                                "permission_endpoint", base + "/permission",
                                "audit_endpoint", base + "/audit")));
            }
        });
    }

    @AfterEach
    void stopServer() {
        personServer.stop(0);
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + personServer.getAddress().getPort());
    }

    private MissionClient client() {
        String agentToken = Jwts.signEdDsa(
                Map.of("alg", "EdDSA", "typ", "aa-agent+jwt", "kid", "ps-1"),
                Map.of(
                        "iss",
                        baseUri().toString(),
                        "sub",
                        "aauth:client@test",
                        "cnf",
                        Map.of("jwk", Jwk.publicKeyToJwk(ephemeralKey.getPublic(), null)),
                        "exp",
                        Instant.now().getEpochSecond() + 3600),
                providerKey.getPrivate());
        AgentBootstrap.Identity identity = new AgentBootstrap.Identity("aauth:client@test", agentToken, ephemeralKey);
        return new MissionClient(baseUri(), () -> identity, Duration.ofSeconds(10));
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

    @Test
    void proposeReturnsTheVerifiedMissionHashWhenAutoApproved() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        personServer.createContext("/agent-missions", exchange -> {
            try (exchange) {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, Map.of("Content-Type", "application/json"), APPROVAL_JSON);
            }
        });

        MissionClient.Mission mission =
                client().propose("Optimize things", Map.of("supply-chain:optimize", "Optimize"), null);

        assertThat(mission.s256()).isEqualTo(S256);
        assertThat(requestBody.get()).contains("\"supply-chain:optimize\"").contains("Optimize things");
    }

    @Test
    void proposeDefersThenPollsToApproval() {
        AtomicInteger polls = new AtomicInteger();
        personServer.createContext("/agent-missions", exchange -> {
            try (exchange) {
                respond(
                        exchange,
                        202,
                        Map.of("Location", "/pending/p1", "Retry-After", "0"),
                        "{\"status\":\"pending\",\"code\":\"1234\",\"pending_id\":\"p1\"}");
            }
        });
        AtomicReference<String> interaction = new AtomicReference<>();
        personServer.createContext("/pending/p1", exchange -> {
            try (exchange) {
                if (polls.incrementAndGet() < 2) {
                    respond(exchange, 202, Map.of("Retry-After", "0"), "{\"status\":\"pending\"}");
                } else {
                    respond(exchange, 200, Map.of("Content-Type", "application/json"), APPROVAL_JSON);
                }
            }
        });

        MissionClient.Mission mission = client().propose(
                        "Do the mission", Map.of("tool", "A tool"), (url, code) -> interaction.set(url + "|" + code));

        assertThat(mission.s256()).isEqualTo(S256);
        assertThat(polls.get()).isEqualTo(2);
        assertThat(interaction.get()).isEqualTo(baseUri() + "/consent?code=1234|1234");
    }

    @Test
    void deniedMissionApprovalThrows() {
        personServer.createContext("/agent-missions", exchange -> {
            try (exchange) {
                respond(
                        exchange,
                        202,
                        Map.of("Location", "/pending/p2", "Retry-After", "0"),
                        "{\"status\":\"pending\",\"code\":\"9999\",\"pending_id\":\"p2\"}");
            }
        });
        personServer.createContext("/pending/p2", exchange -> {
            try (exchange) {
                respond(exchange, 403, Map.of(), "{\"error\":\"denied\"}");
            }
        });

        assertThatExceptionOfType(MissionException.class)
                .isThrownBy(() -> client().propose("Nope", Map.of("tool", "A tool"), null))
                .withMessageContaining("denied");
    }

    @Test
    void permissionInApprovedToolsGrantsImmediately() {
        personServer.createContext("/permission", exchange -> {
            try (exchange) {
                respond(exchange, 200, Map.of("Content-Type", "application/json"), "{\"permission\":\"granted\"}");
            }
        });

        MissionClient.Permission permission = client().permission(
                        new MissionClient.Mission(S256),
                        "supply-chain:optimize",
                        "Optimize laptops",
                        Map.of("product", "laptop"),
                        null);

        assertThat(permission).isEqualTo(MissionClient.Permission.GRANTED);
    }

    @Test
    void deferredPermissionResolvesToTheUsersDenial() {
        personServer.createContext("/permission", exchange -> {
            try (exchange) {
                respond(
                        exchange,
                        202,
                        Map.of("Location", "/pending/p3", "Retry-After", "0"),
                        "{\"status\":\"pending\",\"code\":\"5678\",\"pending_id\":\"p3\","
                                + "\"interaction_url\":\"http://consent.test/c?code=5678\"}");
            }
        });
        personServer.createContext("/pending/p3", exchange -> {
            try (exchange) {
                respond(exchange, 200, Map.of("Content-Type", "application/json"), "{\"permission\":\"denied\"}");
            }
        });
        AtomicReference<String> interaction = new AtomicReference<>();

        MissionClient.Permission permission = client().permission(
                        new MissionClient.Mission(S256),
                        "inventory:purchase",
                        "Purchase stock",
                        Map.of("units", 500),
                        (url, code) -> interaction.set(url + "|" + code));

        assertThat(permission).isEqualTo(MissionClient.Permission.DENIED);
        assertThat(interaction.get()).isEqualTo("http://consent.test/c?code=5678|5678");
    }

    @Test
    void auditPostsAndAcceptsCreated() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        personServer.createContext("/audit", exchange -> {
            try (exchange) {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 201, Map.of(), "{}");
            }
        });

        client().audit(
                        new MissionClient.Mission(S256),
                        "supply-chain:optimize",
                        "Optimized laptops",
                        Map.of("summary", "all good"));

        assertThat(requestBody.get())
                .contains("\"mission_s256\":\"" + S256 + "\"")
                .contains("Optimized laptops");
    }

    @Test
    void unexpectedStatusesThrowWithContext() {
        personServer.createContext("/agent-missions", exchange -> {
            try (exchange) {
                respond(exchange, 500, Map.of(), "{\"error\":\"boom\"}");
            }
        });
        personServer.createContext("/permission", exchange -> {
            try (exchange) {
                respond(exchange, 500, Map.of(), "{\"error\":\"boom\"}");
            }
        });
        personServer.createContext("/audit", exchange -> {
            try (exchange) {
                respond(exchange, 500, Map.of(), "{\"error\":\"boom\"}");
            }
        });
        MissionClient client = client();
        MissionClient.Mission mission = new MissionClient.Mission(S256);

        assertThatExceptionOfType(MissionException.class)
                .isThrownBy(() -> client.propose("x", Map.of("tool", "t"), null))
                .withMessageContaining("HTTP 500");
        assertThatExceptionOfType(MissionException.class)
                .isThrownBy(() -> client.permission(mission, "a", "d", Map.of(), null))
                .withMessageContaining("HTTP 500");
        assertThatExceptionOfType(MissionException.class)
                .isThrownBy(() -> client.audit(mission, "a", "d", Map.of()))
                .withMessageContaining("HTTP 500");
    }

    @Test
    void requestsAreSignedWithTheAgentToken() {
        AtomicReference<List<String>> signatureHeaders = new AtomicReference<>();
        personServer.createContext("/agent-missions", exchange -> {
            try (exchange) {
                signatureHeaders.set(List.of(
                        String.valueOf(exchange.getRequestHeaders().getFirst("Signature-Input")),
                        String.valueOf(exchange.getRequestHeaders().getFirst("Signature-Key"))));
                respond(exchange, 200, Map.of("Content-Type", "application/json"), APPROVAL_JSON);
            }
        });

        client().propose("Signed?", Map.of("tool", "t"), null);

        assertThat(signatureHeaders.get().get(0)).contains("content-digest");
        assertThat(signatureHeaders.get().get(1)).contains("sig=jwt;jwt=");
    }

    @Test
    void completionPostsToTheMissionUrl() {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        personServer.createContext("/agent-missions/", exchange -> {
            try (exchange) {
                path.set(exchange.getRequestURI().getPath());
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, Map.of("Content-Type", "application/json"), "{}");
            }
        });

        client().complete(new MissionClient.Mission(S256), "all done");

        assertThat(path.get()).isEqualTo("/agent-missions/" + S256);
        assertThat(requestBody.get()).contains("\"action\":\"completion\"").contains("all done");
    }

    @Test
    void approvalWithAMismatchedHashIsRejected() {
        Map<String, Object> tampered = new java.util.LinkedHashMap<>(APPROVAL);
        tampered.put("s256", "A".repeat(43));
        personServer.createContext("/agent-missions", exchange -> {
            try (exchange) {
                respond(exchange, 200, Map.of("Content-Type", "application/json"), A2aJson.toJson(tampered));
            }
        });

        assertThatExceptionOfType(MissionException.class)
                .isThrownBy(() -> client().propose("x", Map.of("tool", "t"), null))
                .withMessageContaining("Invalid mission approval");
    }
}
