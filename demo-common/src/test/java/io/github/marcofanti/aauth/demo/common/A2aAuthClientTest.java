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
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import io.github.marcofanti.aauth.tokens.AgentTokens;
import io.github.marcofanti.aauth.tokens.AuthTokens;
import io.github.marcofanti.aauth.tokens.PersonTokens;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The client's draft-11 ladder against a local resource and an in-process Person Server: agent
 * token → {@code requirement=person-token} → person token → resource token → exchange with
 * {@code presented_token} → auth token.
 */
class A2aAuthClientTest {

    private static final String RESOURCE = "https://sca.example";
    private static final String SCOPE = "supply-chain:optimize";

    private final FakePersonServer ps = new FakePersonServer();
    private final KeyPair resourceKey = KeyPairs.generateEd25519();
    private final AAuthInboundVerifier resourceSide = new AAuthInboundVerifier(
            URI.create(RESOURCE + "/"),
            resourceKey,
            "rsk-1",
            AAuthInboundVerifier.Requirement.AUTH_TOKEN,
            ps.jwksFetcher(),
            SCOPE,
            FakePersonServer.PS);
    private HttpServer resource;

    @BeforeEach
    void startResource() throws IOException {
        ps.registerResource(RESOURCE, resourceKey.getPublic(), "rsk-1");
        resource = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resource.start();
    }

    @AfterEach
    void stopResource() {
        resource.stop(0);
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + resource.getAddress().getPort() + "/");
    }

    private AgentBootstrap.Identity identity() {
        KeyPair agentKey = KeyPairs.generateEd25519();
        return new AgentBootstrap.Identity(FakePersonServer.AGENT_ID, ps.agentToken(agentKey), agentKey);
    }

    private A2aAuthClient client(java.util.function.Supplier<AgentBootstrap.Identity> identity) {
        return new A2aAuthClient(endpoint(), RESOURCE, identity, ps.transport(), ps.jwksFetcher());
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

    /** A draft-11 resource: challenges by presented token type; serves a granted auth token. */
    private Map<String, AtomicInteger> stubDraft11Resource() {
        Map<String, AtomicInteger> callsByType = new LinkedHashMap<>();
        resource.createContext("/", exchange -> {
            try (exchange) {
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
                String token = String.valueOf(SignatureKeyHeader.parse(headers.get("Signature-key"))
                        .params()
                        .get("jwt"));
                String typ = String.valueOf(Jwts.parse(token).header().get("typ"));
                callsByType.computeIfAbsent(typ, t -> new AtomicInteger()).incrementAndGet();
                switch (typ) {
                    case AgentTokens.TYPE -> {
                        var challenge = resourceSide.personTokenChallenge();
                        respond(exchange, 401, Map.of(challenge.headerName(), challenge.headerValue()), "{}");
                    }
                    case PersonTokens.TYPE -> {
                        var challenge = resourceSide.authTokenChallenge(headers);
                        respond(exchange, 401, Map.of(challenge.headerName(), challenge.headerValue()), "{}");
                    }
                    default ->
                        respond(
                                exchange,
                                200,
                                Map.of("Content-Type", "application/json"),
                                A2aJson.toJson(JsonRpcResponse.success("1", A2aMessage.agentText("m", "authorized"))));
                }
            }
        });
        return callsByType;
    }

    @Test
    void climbsThePersonTokenAndAuthTokenLadder() throws Exception {
        Map<String, AtomicInteger> calls = stubDraft11Resource();
        AgentBootstrap.Identity fixed = identity();
        A2aAuthClient client = client(() -> fixed);

        assertThat(client.sendText("optimize", null)).isEqualTo("authorized");

        assertThat(calls.get(AgentTokens.TYPE).get()).isEqualTo(1);
        assertThat(calls.get(PersonTokens.TYPE).get()).isEqualTo(1);
        assertThat(calls.get(AuthTokens.TYPE).get()).isEqualTo(1);
        assertThat(ps.personTokenRequests.get()).isEqualTo(1);
        assertThat(ps.authTokenRequests.get()).isEqualTo(1);
        assertThat(Jwts.parse(ps.lastPresentedToken).header().get("typ")).isEqualTo(PersonTokens.TYPE);
    }

    @Test
    void cachedAuthTokenServesTheNextCallDirectly() throws Exception {
        Map<String, AtomicInteger> calls = stubDraft11Resource();
        AgentBootstrap.Identity fixed = identity();
        A2aAuthClient client = client(() -> fixed);

        client.sendText("first", null);
        client.sendText("second", null);

        assertThat(calls.get(AgentTokens.TYPE).get()).isEqualTo(1);
        assertThat(calls.get(AuthTokens.TYPE).get()).isEqualTo(2);
        assertThat(ps.authTokenRequests.get()).isEqualTo(1);
    }

    @Test
    void identityRotationDropsCachedPersonAndAuthTokens() throws Exception {
        Map<String, AtomicInteger> calls = stubDraft11Resource();
        AtomicReference<AgentBootstrap.Identity> active = new AtomicReference<>(identity());
        A2aAuthClient client = client(active::get);

        client.sendText("first", null);
        active.set(identity()); // refresh: new key, so tokens bound to the old one are useless
        client.sendText("second", null);

        assertThat(calls.get(AgentTokens.TYPE).get()).isEqualTo(2);
        assertThat(ps.personTokenRequests.get()).isEqualTo(2);
        assertThat(ps.authTokenRequests.get()).isEqualTo(2);
    }

    @Test
    void non401FailuresPassThrough() {
        resource.createContext("/", exchange -> {
            try (exchange) {
                respond(exchange, 500, Map.of(), "{\"error\":\"boom\"}");
            }
        });
        AgentBootstrap.Identity fixed = identity();

        assertThatExceptionOfType(A2aClientException.class)
                .isThrownBy(() -> client(() -> fixed).sendText("hi", null))
                .satisfies(e -> assertThat(e.statusCode()).isEqualTo(500));
    }

    @Test
    void unauthorizedWithoutRequirementPassesThrough() {
        resource.createContext("/", exchange -> {
            try (exchange) {
                respond(exchange, 401, Map.of(), "{\"error\":\"nope\"}");
            }
        });
        AgentBootstrap.Identity fixed = identity();

        assertThatExceptionOfType(A2aClientException.class)
                .isThrownBy(() -> client(() -> fixed).sendText("hi", null))
                .satisfies(e -> assertThat(e.statusCode()).isEqualTo(401));
        assertThat(ps.personTokenRequests.get()).isZero();
    }
}
