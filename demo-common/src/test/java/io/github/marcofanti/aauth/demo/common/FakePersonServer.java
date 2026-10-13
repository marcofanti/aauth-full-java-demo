package io.github.marcofanti.aauth.demo.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.marcofanti.aauth.http.AAuthResponse;
import io.github.marcofanti.aauth.http.HttpTransport;
import io.github.marcofanti.aauth.metadata.Metadata;
import io.github.marcofanti.aauth.signing.JwksFetcher;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.SignatureKeyHeader;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import io.github.marcofanti.aauth.tokens.AgentTokens;
import io.github.marcofanti.aauth.tokens.AuthTokens;
import io.github.marcofanti.aauth.tokens.PersonTokens;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process AAuth draft-11 Person Server (and co-located agent provider) at
 * {@code https://ps.example} for tests. It mints real tokens with its own key, serves JWKS for
 * itself and for registered resources, and answers {@code aauth-person.json},
 * {@code /person-token} and {@code /auth-token} through an {@link HttpTransport} — so the
 * library's strict HTTPS identifier rules hold without a network.
 */
final class FakePersonServer {

    static final String PS = "https://ps.example";
    static final String KID = "ps-test-1";
    static final String AGENT_ID = "aauth:backend@agent.example";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final KeyPair psKey = KeyPairs.generateEd25519();
    private final Map<String, Map<String, Object>> jwksById = new ConcurrentHashMap<>();
    final AtomicInteger personTokenRequests = new AtomicInteger();
    final AtomicInteger authTokenRequests = new AtomicInteger();
    volatile String lastPresentedToken;

    FakePersonServer() {
        jwksById.put(PS, jwks(psKey.getPublic(), KID));
    }

    /** Publishes a resource's signing key, as its {@code aauth-resource.json} JWKS would. */
    void registerResource(String resourceId, PublicKey key, String kid) {
        jwksById.put(resourceId, jwks(key, kid));
    }

    JwksFetcher jwksFetcher() {
        return (id, dwk, kid) -> jwksById.get(id);
    }

    String agentToken(KeyPair agentKey) {
        return AgentTokens.create(AgentTokens.Spec.builder(PS, AGENT_ID, cnf(agentKey), psKey.getPrivate(), KID)
                .ps(PS)
                .exp(inOneHour())
                .build());
    }

    String personToken(KeyPair agentKey, String resource) {
        return personToken(agentKey, resource, inOneHour());
    }

    /** A person token bounded by the presenting agent token's {@code exp} (PS-11). */
    String personToken(KeyPair agentKey, String resource, long agentExp) {
        return PersonTokens.create(
                PersonTokens.Spec.builder(PS, resource, subFor(resource), cnf(agentKey), psKey.getPrivate(), KID)
                        .agentExp(agentExp)
                        .build());
    }

    String authToken(KeyPair agentKey, String resource, String scope) {
        return AuthTokens.create(AuthTokens.Spec.builder(PS, resource, subFor(resource), PS)
                .cnfJwk(cnf(agentKey))
                .signingKey(psKey.getPrivate(), KID)
                .dwk(AuthTokens.DWK_PERSON)
                .scope(scope)
                .bounds(inOneHour(), inOneHour())
                .build());
    }

    /** Directed identifier: stable per resource (PS-20). */
    static String subFor(String resource) {
        return "person-" + Integer.toHexString(resource.hashCode());
    }

    HttpTransport transport() {
        return (method, url, headers, body) -> switch (url) {
            case PS + "/.well-known/aauth-person.json" ->
                json(
                        200,
                        Metadata.personServer(PS, PS + "/person-token", PS + "/auth-token", PS + "/jwks.json")
                                .build());
            case PS + "/person-token" -> issuePersonToken(headers, body);
            case PS + "/auth-token" -> issueAuthToken(headers, body);
            default -> json(404, Map.of("error", "not_found", "detail", url));
        };
    }

    private AAuthResponse issuePersonToken(Map<String, String> headers, byte[] body) throws IOException {
        personTokenRequests.incrementAndGet();
        String resource = String.valueOf(parse(body).get("resource"));
        Map<String, Object> agentClaims = Jwts.parse(signerToken(headers)).payload();
        long agentExp = ((Number) agentClaims.get("exp")).longValue();
        return json(
                200, Map.of("person_token", personToken(signerKey(headers), resource, agentExp), "expires_in", 3600));
    }

    private AAuthResponse issueAuthToken(Map<String, String> headers, byte[] body) throws IOException {
        authTokenRequests.incrementAndGet();
        Map<String, Object> request = parse(body);
        lastPresentedToken = String.valueOf(request.get("presented_token"));
        Map<String, Object> resourceToken =
                Jwts.parse(String.valueOf(request.get("resource_token"))).payload();
        String resource = String.valueOf(resourceToken.get("iss"));
        String scope = String.valueOf(resourceToken.get("scope"));
        return json(200, Map.of("auth_token", authToken(signerKey(headers), resource, scope), "expires_in", 3600));
    }

    /** The token the caller signed with (its Signature-Key {@code jwt}). */
    private static String signerToken(Map<String, String> headers) {
        String signatureKey = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("Signature-Key")) {
                signatureKey = entry.getValue();
            }
        }
        return String.valueOf(SignatureKeyHeader.parse(signatureKey).params().get("jwt"));
    }

    /** The calling agent's key: the {@code cnf.jwk} of the token in its Signature-Key. */
    private static KeyPair signerKey(Map<String, String> headers) {
        String token = signerToken(headers);
        Map<?, ?> cnf = (Map<?, ?>) Jwts.parse(token).payload().get("cnf");
        Map<String, Object> jwk = new LinkedHashMap<>();
        ((Map<?, ?>) cnf.get("jwk")).forEach((key, value) -> jwk.put(String.valueOf(key), value));
        return new KeyPair(Jwk.toPublicKey(jwk), null);
    }

    private static Map<String, Object> cnf(KeyPair agentKey) {
        return Jwk.publicKeyToJwk(agentKey.getPublic(), null);
    }

    private static Map<String, Object> jwks(PublicKey key, String kid) {
        return Jwk.generateJwks(List.of(Jwk.publicKeyToJwk(key, kid)));
    }

    private static long inOneHour() {
        return Instant.now().getEpochSecond() + 3600;
    }

    private static Map<String, Object> parse(byte[] body) throws IOException {
        return MAPPER.readValue(body, OBJECT);
    }

    private static AAuthResponse json(int status, Map<String, Object> value) throws IOException {
        return new AAuthResponse(
                status,
                Map.of("Content-Type", "application/json"),
                MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
    }
}
