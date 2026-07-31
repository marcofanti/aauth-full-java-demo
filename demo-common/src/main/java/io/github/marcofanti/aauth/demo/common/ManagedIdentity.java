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
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An agent identity that keeps itself fresh: registers at construction (via
 * {@link AgentBootstrap}) and thereafter renews the {@code aa-agent+jwt} before expiry using
 * the {@code jkt-jwt} scheme — a new ephemeral key pair is generated, the persistent stable
 * key signs a short-lived delegation JWT ({@code typ jkt-s256+jwt}, {@code iss
 * urn:jkt:sha-256:<stable thumbprint>}, {@code cnf.jwk} = new ephemeral key), and
 * {@code POST /refresh} is signed with the new ephemeral key. No human approval is involved.
 *
 * <p>Consumers read {@link #current()} per call: after a refresh they pick up the new token
 * and key, and {@link A2aAuthClient} drops cached auth tokens (their {@code cnf.jwk} binds
 * the old ephemeral key).
 */
public final class ManagedIdentity implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ManagedIdentity.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final Duration DELEGATION_TTL = Duration.ofSeconds(300);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final AgentBootstrap.Config config;
    private final Duration refreshLeeway;
    private final KeyPair stableKeyPair;
    private final AtomicReference<AgentBootstrap.Identity> current = new AtomicReference<>();
    private final ScheduledExecutorService scheduler;
    private final HttpClient http;

    public static ManagedIdentity register(AgentBootstrap.Config config) {
        return new ManagedIdentity(config, Duration.ofSeconds(120));
    }

    ManagedIdentity(AgentBootstrap.Config config, Duration refreshLeeway) {
        this.config = config;
        this.refreshLeeway = refreshLeeway;
        this.stableKeyPair = StableKeys.loadOrCreate(config.keyDirectory(), config.agentName());
        // The Person Server's HTTP/1.1 parser mishandles the JDK client's default h2c upgrade.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.current.set(AgentBootstrap.register(config));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, config.agentName() + "-token-refresh");
            thread.setDaemon(true);
            return thread;
        });
        scheduleNextRefresh();
    }

    public AgentBootstrap.Identity current() {
        return current.get();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void scheduleNextRefresh() {
        long secondsToExpiry = secondsToExpiry(current.get().agentToken());
        long delay = Math.max(secondsToExpiry - refreshLeeway.toSeconds(), Math.max(secondsToExpiry / 2, 5));
        log.info("Agent token for \"{}\" expires in {}s; refresh in {}s", config.agentName(), secondsToExpiry, delay);
        scheduler.schedule(this::refreshSafely, delay, TimeUnit.SECONDS);
    }

    private void refreshSafely() {
        try {
            refresh();
            scheduleNextRefresh();
        } catch (RuntimeException e) {
            log.warn(
                    "Agent token refresh failed for \"{}\" ({}); retrying in {}s",
                    config.agentName(),
                    e.getMessage(),
                    RETRY_DELAY.toSeconds());
            scheduler.schedule(this::refreshSafely, RETRY_DELAY.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private void refresh() {
        URI refreshEndpoint = refreshEndpoint();
        KeyPair newEphemeral = KeyPairs.generateEd25519();
        String delegation = delegationJwt(newEphemeral);

        Map<String, String> headers = io.github.marcofanti.aauth.signing.RequestSigner.sign(
                SignRequest.builder("POST", refreshEndpoint.toString())
                        .keyPair(newEphemeral)
                        .scheme(new SignatureScheme.JktJwt(delegation))
                        .build());
        HttpRequest.Builder builder = HttpRequest.newBuilder(refreshEndpoint)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody());
        headers.forEach(builder::header);

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new BootstrapException("POST " + refreshEndpoint + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BootstrapException("Interrupted refreshing agent token", e);
        }
        if (response.statusCode() != 200) {
            throw new BootstrapException("Refresh returned HTTP " + response.statusCode() + ": " + response.body());
        }
        Object token = parseObject(response.body()).get("agent_token");
        if (token == null) {
            throw new BootstrapException("Refresh response has no agent_token: " + response.body());
        }
        String agentId = Jwts.parse(token.toString()).claim("sub");
        current.set(new AgentBootstrap.Identity(agentId, token.toString(), newEphemeral));
        log.info("Refreshed agent token for \"{}\" ({})", config.agentName(), agentId);
    }

    private String delegationJwt(KeyPair newEphemeral) {
        Map<String, Object> stableJwk = jwkWithoutKid(stableKeyPair);
        Map<String, Object> ephemeralJwk = jwkWithoutKid(newEphemeral);
        long now = Instant.now().getEpochSecond();
        return Jwts.signEdDsa(
                Map.of("alg", "EdDSA", "typ", "jkt-s256+jwt", "jwk", stableJwk),
                Map.of(
                        "iss",
                        "urn:jkt:sha-256:" + Jwk.thumbprint(stableJwk),
                        "cnf",
                        Map.of("jwk", ephemeralJwk),
                        "iat",
                        now,
                        "exp",
                        now + DELEGATION_TTL.toSeconds()),
                stableKeyPair.getPrivate());
    }

    private URI refreshEndpoint() {
        URI metadataUri = config.personServerBase().resolve("/.well-known/aauth-agent.json");
        try {
            HttpResponse<String> response =
                    http.send(HttpRequest.newBuilder(metadataUri).GET().build(), HttpResponse.BodyHandlers.ofString());
            Object endpoint = parseObject(response.body()).get("refresh_endpoint");
            if (endpoint == null) {
                throw new BootstrapException("Agent Provider metadata has no refresh_endpoint");
            }
            return URI.create(endpoint.toString());
        } catch (IOException e) {
            throw new BootstrapException("Failed to fetch Agent Provider metadata: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BootstrapException("Interrupted fetching Agent Provider metadata", e);
        }
    }

    private static Map<String, Object> jwkWithoutKid(KeyPair keyPair) {
        Map<String, Object> jwk = new LinkedHashMap<>(Jwk.publicKeyToJwk(keyPair.getPublic(), null));
        jwk.remove("kid");
        return jwk;
    }

    private static long secondsToExpiry(String token) {
        Object exp = Jwts.parse(token).payload().get("exp");
        if (exp instanceof Number expiry) {
            return Math.max(expiry.longValue() - Instant.now().getEpochSecond(), 0);
        }
        return Duration.ofHours(24).toSeconds();
    }

    private static Map<String, Object> parseObject(String json) {
        try {
            return MAPPER.readValue(json, OBJECT);
        } catch (IOException e) {
            throw new BootstrapException("Malformed JSON from Agent Provider: " + json, e);
        }
    }
}
