package io.github.marcofanti.aauth.demo.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.marcofanti.aauth.signing.SignRequest;
import io.github.marcofanti.aauth.signing.SignatureScheme;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signed client for the Person Server's mission layer: propose a mission for user approval,
 * check per-action permission against the mission's approved tools, and append audit entries
 * to the mission log.
 *
 * <p>All calls are RFC 9421-signed {@code scheme=jwt} with the agent identity (read per call,
 * so token refresh composes). Deferred (202) responses — mission approval and out-of-scope
 * permission checks — surface the consent URL and code via {@code onInteraction}, then block
 * polling the pending URL until the user decides.
 */
public final class MissionClient {

    /** An approved mission, referenced by approver and content hash in later calls. */
    public record Mission(String approver, String s256) {}

    /** Terminal outcome of a permission check; a user denial is a result, not an error. */
    public enum Permission {
        GRANTED,
        DENIED
    }

    private static final Logger log = LoggerFactory.getLogger(MissionClient.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final Pattern MISSION_HEADER_PARAM = Pattern.compile("(approver|s256)=\"([^\"]*)\"");
    private static final Duration DEFAULT_POLL_DELAY = Duration.ofSeconds(2);

    private final URI personServerBase;
    private final Supplier<AgentBootstrap.Identity> identitySupplier;
    private final Duration decisionTimeout;
    private final HttpClient http;

    public MissionClient(URI personServerBase, Supplier<AgentBootstrap.Identity> identitySupplier) {
        this(personServerBase, identitySupplier, Duration.ofMinutes(5));
    }

    public MissionClient(
            URI personServerBase, Supplier<AgentBootstrap.Identity> identitySupplier, Duration decisionTimeout) {
        this.personServerBase = personServerBase;
        this.identitySupplier = identitySupplier;
        this.decisionTimeout = decisionTimeout;
        // The Person Server's HTTP/1.1 parser mishandles the JDK client's default h2c upgrade.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Proposes a mission; blocks through user approval when the Person Server defers.
     *
     * @param tools tool name → description, recorded as the mission's approved tools
     * @param onInteraction receives (consent URL, code) when the user must approve; may be null
     */
    public Mission propose(String description, Map<String, String> tools, BiConsumer<String, String> onInteraction) {
        List<Map<String, String>> toolSpecs = new ArrayList<>();
        tools.forEach((name, toolDescription) -> toolSpecs.add(Map.of("name", name, "description", toolDescription)));
        byte[] body = toBytes(Map.of("description", description, "tools", toolSpecs));
        HttpResponse<String> response = sendSigned("POST", personServerBase.resolve("/mission"), body);
        return switch (response.statusCode()) {
            case 200 -> missionFrom(response);
            case 202 -> pollForMission(response, onInteraction);
            default ->
                throw new MissionException(
                        "Mission proposal failed: HTTP " + response.statusCode() + " " + response.body());
        };
    }

    /**
     * Asks permission for one action under the mission. Actions in the mission's approved tools
     * are granted immediately; anything else defers to the user, whose decision is the result.
     */
    public Permission permission(
            Mission mission,
            String action,
            String description,
            Map<String, Object> parameters,
            BiConsumer<String, String> onInteraction) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", action);
        request.put("description", description);
        if (parameters != null && !parameters.isEmpty()) {
            request.put("parameters", parameters);
        }
        request.put("mission", Map.of("approver", mission.approver(), "s256", mission.s256()));
        HttpResponse<String> response = sendSigned("POST", personServerBase.resolve("/permission"), toBytes(request));
        return switch (response.statusCode()) {
            case 200 -> permissionFrom(response);
            case 202 -> pollForPermission(response, onInteraction);
            default ->
                throw new MissionException("Permission check for \"" + action + "\" failed: HTTP "
                        + response.statusCode() + " " + response.body());
        };
    }

    /** Appends an audit entry to the mission log (fire-and-record; the PS returns 201). */
    public void audit(Mission mission, String action, String description, Map<String, Object> result) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("mission", Map.of("approver", mission.approver(), "s256", mission.s256()));
        request.put("action", action);
        request.put("description", description);
        if (result != null && !result.isEmpty()) {
            request.put("result", result);
        }
        HttpResponse<String> response = sendSigned("POST", personServerBase.resolve("/audit"), toBytes(request));
        if (response.statusCode() != 201) {
            throw new MissionException(
                    "Audit entry for \"" + action + "\" failed: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private Mission pollForMission(HttpResponse<String> deferred, BiConsumer<String, String> onInteraction) {
        Deferral deferral = Deferral.from(deferred, personServerBase);
        log.info("Mission proposal deferred; user approval at {} (code {})", deferral.consentUrl(), deferral.code());
        notifyInteraction(onInteraction, deferral);
        HttpResponse<String> terminal = pollPending(deferral, "mission approval");
        return missionFrom(terminal);
    }

    private Permission pollForPermission(HttpResponse<String> deferred, BiConsumer<String, String> onInteraction) {
        Deferral deferral = Deferral.from(deferred, personServerBase);
        log.info("Permission deferred; user decision at {} (code {})", deferral.consentUrl(), deferral.code());
        notifyInteraction(onInteraction, deferral);
        HttpResponse<String> terminal = pollPending(deferral, "permission");
        return permissionFrom(terminal);
    }

    private static void notifyInteraction(BiConsumer<String, String> onInteraction, Deferral deferral) {
        if (onInteraction != null) {
            onInteraction.accept(deferral.consentUrl(), deferral.code());
        }
    }

    private HttpResponse<String> pollPending(Deferral deferral, String what) {
        long deadline = System.nanoTime() + decisionTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            sleep(deferral.retryDelay());
            HttpResponse<String> poll = sendSigned("GET", deferral.pendingUrl(), null);
            switch (poll.statusCode()) {
                case 200 -> {
                    return poll;
                }
                case 202 -> log.debug("Still pending: {}", what);
                case 403 -> throw new MissionException("The " + what + " request was denied: " + poll.body());
                case 410 -> throw new MissionException("The " + what + " request expired");
                default ->
                    throw new MissionException("Polling " + deferral.pendingUrl() + " failed: HTTP " + poll.statusCode()
                            + " " + poll.body());
            }
        }
        throw new MissionException("No user decision on the " + what + " within " + decisionTimeout.toSeconds() + "s");
    }

    private static Mission missionFrom(HttpResponse<String> response) {
        String header = response.headers()
                .firstValue("AAuth-Mission")
                .orElseThrow(() -> new MissionException("Mission response without AAuth-Mission header"));
        String approver = null;
        String s256 = null;
        Matcher matcher = MISSION_HEADER_PARAM.matcher(header);
        while (matcher.find()) {
            if ("approver".equals(matcher.group(1))) {
                approver = matcher.group(2);
            } else {
                s256 = matcher.group(2);
            }
        }
        if (approver == null || s256 == null) {
            throw new MissionException("Malformed AAuth-Mission header: " + header);
        }
        return new Mission(approver, s256);
    }

    private static Permission permissionFrom(HttpResponse<String> response) {
        Object permission = parseObject(response.body()).get("permission");
        return switch (String.valueOf(permission)) {
            case "granted" -> Permission.GRANTED;
            case "denied" -> Permission.DENIED;
            default -> throw new MissionException("Unexpected permission response: " + response.body());
        };
    }

    private HttpResponse<String> sendSigned(String method, URI target, byte[] body) {
        AgentBootstrap.Identity identity = identitySupplier.get();
        Map<String, String> baseHeaders = body == null ? Map.of() : Map.of("Content-Type", "application/json");
        Map<String, String> signedHeaders =
                io.github.marcofanti.aauth.signing.RequestSigner.sign(SignRequest.builder(method, target.toString())
                        .headers(baseHeaders)
                        .body(body)
                        .keyPair(identity.ephemeralKeyPair())
                        .scheme(new SignatureScheme.Jwt(identity.agentToken()))
                        .additionalComponents(body == null ? List.of() : List.of("content-digest", "content-type"))
                        .build());
        Map<String, String> allHeaders = new LinkedHashMap<>(baseHeaders);
        allHeaders.putAll(signedHeaders);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(15))
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        allHeaders.forEach(builder::header);
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new MissionException(method + " " + target + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MissionException(method + " " + target + " interrupted", e);
        }
    }

    /** The pending/consent coordinates of a 202 deferred response. */
    private record Deferral(URI pendingUrl, String consentUrl, String code, Duration retryDelay) {

        static Deferral from(HttpResponse<String> response, URI base) {
            Map<String, Object> body = parseObject(response.body());
            String code = stringOrNull(body.get("code"));
            URI pendingUrl = response.headers()
                    .firstValue("Location")
                    .map(base::resolve)
                    .or(() -> java.util.Optional.ofNullable(stringOrNull(body.get("pending_url")))
                            .map(base::resolve))
                    .orElseThrow(() -> new MissionException("202 deferred response without a pending URL"));
            String consentUrl = stringOrNull(body.get("interaction_url"));
            if (consentUrl == null && code != null) {
                consentUrl = base.resolve("/consent?code=" + code).toString();
            }
            Duration delay = response.headers()
                    .firstValue("Retry-After")
                    .map(seconds -> Duration.ofSeconds(Long.parseLong(seconds.strip())))
                    .orElse(DEFAULT_POLL_DELAY);
            return new Deferral(pendingUrl, consentUrl, code, delay);
        }
    }

    private static Map<String, Object> parseObject(String json) {
        try {
            return MAPPER.readValue(json, OBJECT);
        } catch (IOException e) {
            throw new MissionException("Malformed JSON from the Person Server: " + json, e);
        }
    }

    private static byte[] toBytes(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MissionException("Failed to serialize mission request", e);
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MissionException("Interrupted while waiting for a user decision");
        }
    }
}
