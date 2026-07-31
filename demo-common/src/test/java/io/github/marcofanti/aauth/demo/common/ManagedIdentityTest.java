package io.github.marcofanti.aauth.demo.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.SignatureKeyHeader;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedIdentityTest {

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

    private String agentToken(long ttlSeconds) {
        return Jwts.signEdDsa(
                Map.of("alg", "EdDSA", "typ", "aa-agent+jwt", "kid", "as-1"),
                Map.of(
                        "iss",
                        base().toString(),
                        "sub",
                        "aauth:refresh@test",
                        "exp",
                        Instant.now().getEpochSecond() + ttlSeconds),
                providerKey.getPrivate());
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void refreshesTokenWithJktJwtDelegation() throws Exception {
        server.createContext("/.well-known/aauth-agent.json", exchange -> {
            try (exchange) {
                json(exchange, 200, """
                        {"issuer":"%s","registration_endpoint":"%s/register","refresh_endpoint":"%s/refresh"}
                        """.formatted(base(), base(), base()).strip());
            }
        });
        server.createContext("/register", exchange -> {
            try (exchange) {
                json(exchange, 200, "{\"agent_token\":\"" + agentToken(8) + "\"}");
            }
        });
        AtomicReference<String> delegationJwt = new AtomicReference<>();
        server.createContext("/refresh", exchange -> {
            try (exchange) {
                String signatureKey = exchange.getRequestHeaders().getFirst("Signature-Key");
                SignatureKeyHeader.Parsed parsed = SignatureKeyHeader.parse(signatureKey);
                if (!"jkt-jwt".equals(parsed.scheme())) {
                    json(exchange, 401, "{\"error\":\"wrong_scheme\"}");
                    return;
                }
                delegationJwt.set(parsed.params().get("jwt"));
                json(exchange, 200, "{\"agent_token\":\"" + agentToken(3600) + "\"}");
            }
        });

        try (ManagedIdentity identity = new ManagedIdentity(
                new AgentBootstrap.Config(base(), keyDir, "refresh-agent", Duration.ofSeconds(10)),
                Duration.ofSeconds(6))) {
            String initialToken = identity.current().agentToken();
            KeyPair initialEphemeral = identity.current().ephemeralKeyPair();

            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline
                    && identity.current().agentToken().equals(initialToken)) {
                Thread.sleep(200);
            }

            assertThat(identity.current().agentToken()).isNotEqualTo(initialToken);
            assertThat(identity.current().ephemeralKeyPair()).isNotSameAs(initialEphemeral);
            assertThat(identity.current().agentId()).isEqualTo("aauth:refresh@test");

            // The delegation JWT: stable-key-signed, typ jkt-s256+jwt, iss = stable thumbprint.
            Jwts.Decoded delegation = Jwts.parse(delegationJwt.get());
            assertThat(delegation.header().get("typ")).isEqualTo("jkt-s256+jwt");
            Object headerJwk = delegation.header().get("jwk");
            assertThat(headerJwk).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> stableJwk = (Map<String, Object>) headerJwk;
            assertThat(delegation.payload().get("iss")).isEqualTo("urn:jkt:sha-256:" + Jwk.thumbprint(stableJwk));
            assertThat(delegation.payload().get("cnf")).isInstanceOf(Map.class);
        }
    }
}
