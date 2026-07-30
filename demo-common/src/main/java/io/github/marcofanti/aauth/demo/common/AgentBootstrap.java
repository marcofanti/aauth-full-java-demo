package io.github.marcofanti.aauth.demo.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.marcofanti.aauth.signing.Jwts;
import io.github.marcofanti.aauth.signing.SignRequest;
import io.github.marcofanti.aauth.signing.SignatureScheme;
import io.github.marcofanti.aauth.signing.keys.Jwk;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers this service with the AAuth Agent Provider (Person Server) at startup:
 *
 * <ol>
 *   <li>fetch {@code /.well-known/aauth-agent.json} (with retries — the PS may still be starting),
 *   <li>{@code POST /register} with the stable public key, signed {@code hwk} with a fresh
 *       ephemeral key,
 *   <li>on 202, poll the {@code Location} URL (same ephemeral key) until a human or the
 *       {@code /person} API approves the registration,
 *   <li>hold the resulting {@code aa-agent+jwt}, whose {@code cnf.jwk} binds the ephemeral key.
 * </ol>
 *
 * <p>Token refresh ({@code jkt-jwt} with a stable-key delegation JWT) is not implemented yet;
 * agent tokens outlive a demo session.
 */
public final class AgentBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private static final int METADATA_ATTEMPTS = 15;
    private static final Duration METADATA_RETRY_DELAY = Duration.ofSeconds(2);
    private static final Duration DEFAULT_POLL_DELAY = Duration.ofSeconds(5);

    /** The registered identity: agent id, the {@code aa-agent+jwt}, and the bound ephemeral key. */
    public record Identity(String agentId, String agentToken, KeyPair ephemeralKeyPair) {}

    public record Config(URI personServerBase, Path keyDirectory, String agentName, Duration approvalTimeout) {}

    private AgentBootstrap() {}

    public static Identity register(Config config) {
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        KeyPair stableKeyPair = StableKeys.loadOrCreate(config.keyDirectory(), config.agentName());
        KeyPair ephemeralKeyPair = KeyPairs.generateEd25519();

        Map<String, Object> metadata = fetchMetadata(http, config.personServerBase());
        URI registerEndpoint = URI.create(String.valueOf(metadata.get("registration_endpoint")));

        Map<String, Object> stablePub = new LinkedHashMap<>(Jwk.publicKeyToJwk(stableKeyPair.getPublic(), null));
        stablePub.remove("kid");
        byte[] body = toBytes(Map.of("stable_pub", stablePub, "agent_name", config.agentName()));

        HttpResponse<String> response = sendSigned(http, "POST", registerEndpoint, body, ephemeralKeyPair);
        String token =
                switch (response.statusCode()) {
                    case 200 -> tokenFrom(response, registerEndpoint);
                    case 202 -> pollForApproval(http, config, response, ephemeralKeyPair);
                    default ->
                        throw new BootstrapException("Registration at " + registerEndpoint + " failed: HTTP "
                                + response.statusCode() + " " + response.body());
                };

        String agentId = Jwts.parse(token).claim("sub");
        log.info("Registered as {} with the Agent Provider at {}", agentId, config.personServerBase());
        return new Identity(agentId, token, ephemeralKeyPair);
    }

    private static Map<String, Object> fetchMetadata(HttpClient http, URI personServerBase) {
        URI metadataUri = personServerBase.resolve("/.well-known/aauth-agent.json");
        BootstrapException lastFailure = null;
        for (int attempt = 1; attempt <= METADATA_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(metadataUri).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return parseObject(response.body());
                }
                lastFailure = new BootstrapException(
                        "Agent Provider metadata at " + metadataUri + " returned HTTP " + response.statusCode());
            } catch (IOException e) {
                lastFailure = new BootstrapException(
                        "Agent Provider unreachable at " + metadataUri + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BootstrapException("Interrupted fetching Agent Provider metadata", e);
            }
            log.info("Waiting for Agent Provider at {} (attempt {}/{})", personServerBase, attempt, METADATA_ATTEMPTS);
            sleep(METADATA_RETRY_DELAY);
        }
        throw lastFailure;
    }

    private static String pollForApproval(
            HttpClient http, Config config, HttpResponse<String> accepted, KeyPair ephemeralKeyPair) {
        URI pendingUrl = accepted.headers()
                .firstValue("Location")
                .map(location -> config.personServerBase().resolve(location))
                .orElseThrow(() -> new BootstrapException("202 registration response without Location header"));
        Duration delay = accepted.headers()
                .firstValue("Retry-After")
                .map(seconds -> Duration.ofSeconds(Long.parseLong(seconds.strip())))
                .orElse(DEFAULT_POLL_DELAY);
        log.info(
                "Registration pending approval — approve \"{}\" in the Person Server "
                        + "(UI /ui or POST /person/registrations/<id>/approve)",
                config.agentName());

        long deadline = System.nanoTime() + config.approvalTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            sleep(delay);
            HttpResponse<String> poll = sendSigned(http, "GET", pendingUrl, null, ephemeralKeyPair);
            switch (poll.statusCode()) {
                case 200 -> {
                    return tokenFrom(poll, pendingUrl);
                }
                case 202 -> log.info("Still pending approval for \"{}\"", config.agentName());
                case 403 -> throw new BootstrapException("Registration for " + config.agentName() + " was denied");
                case 410 -> throw new BootstrapException("Registration for " + config.agentName() + " expired");
                default ->
                    throw new BootstrapException(
                            "Polling " + pendingUrl + " failed: HTTP " + poll.statusCode() + " " + poll.body());
            }
        }
        throw new BootstrapException("Registration for " + config.agentName() + " not approved within "
                + config.approvalTimeout().toSeconds() + "s");
    }

    private static HttpResponse<String> sendSigned(
            HttpClient http, String method, URI target, byte[] body, KeyPair keyPair) {
        Map<String, String> baseHeaders = body == null ? Map.of() : Map.of("Content-Type", "application/json");
        Map<String, String> signedHeaders =
                io.github.marcofanti.aauth.signing.RequestSigner.sign(SignRequest.builder(method, target.toString())
                        .headers(baseHeaders)
                        .body(body)
                        .keyPair(keyPair)
                        .scheme(new SignatureScheme.Hwk())
                        .additionalComponents(body == null ? List.of() : List.of("content-digest", "content-type"))
                        .build());
        Map<String, String> allHeaders = new LinkedHashMap<>(baseHeaders);
        allHeaders.putAll(signedHeaders);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(15))
                // The Person Server's HTTP/1.1 parser (h11) is strict; skip the h2c upgrade dance.
                .version(HttpClient.Version.HTTP_1_1)
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        allHeaders.forEach(builder::header);
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new BootstrapException(method + " " + target + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BootstrapException(method + " " + target + " interrupted", e);
        }
    }

    private static String tokenFrom(HttpResponse<String> response, URI source) {
        Object token = parseObject(response.body()).get("agent_token");
        if (token == null) {
            throw new BootstrapException("Response from " + source + " has no agent_token: " + response.body());
        }
        return token.toString();
    }

    private static Map<String, Object> parseObject(String json) {
        try {
            return MAPPER.readValue(json, OBJECT);
        } catch (IOException e) {
            throw new BootstrapException("Malformed JSON from Agent Provider: " + json, e);
        }
    }

    private static byte[] toBytes(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BootstrapException("Failed to serialize registration body", e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BootstrapException("Interrupted while waiting for the Agent Provider", e);
        }
    }
}
