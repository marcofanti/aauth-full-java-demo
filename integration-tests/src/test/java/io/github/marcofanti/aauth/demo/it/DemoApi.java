package io.github.marcofanti.aauth.demo.it;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Thin client for the live demo services under test (started by scripts/run-demo.sh).
 * Hostnames come from the {@code demo.*.host} system properties (run-tests.sh passes them
 * from hosts.env), defaulting to the uma.lab names.
 */
final class DemoApi {

    static final URI BACKEND = URI.create("http://" + host("demo.portal.host", "portal.uma.lab") + ":8000");
    static final URI SUPPLY_CHAIN_AGENT =
            URI.create("http://" + host("demo.gateway.host", "gateway.uma.lab") + ":9999/");
    static final URI MARKET_ANALYSIS_AGENT =
            URI.create("http://" + host("demo.gateway.host", "gateway.uma.lab") + ":9998/");
    static final URI PERSON_SERVER = URI.create("http://" + host("demo.ps.host", "ps.uma.lab") + ":8765");

    private static String host(String property, String fallback) {
        return System.getProperty(property, fallback);
    }

    // HTTP/1.1: the Person Server's parser (uvicorn/h11) mishandles the JDK client's default
    // h2c upgrade, dropping POST bodies.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private DemoApi() {}

    record Progress(String status, String interactionUrl, String interactionCode, String error) {}

    record RunResult(String finalStatus, Set<String> statusesSeen, String report, String error) {}

    record MissionProgress(
            String status,
            String missionS256,
            String interactionUrl,
            String interactionCode,
            java.util.List<Map<String, Object>> steps,
            String error) {}

    static HttpResponse<String> get(URI uri) {
        return send(HttpRequest.newBuilder(uri).GET().build());
    }

    static HttpResponse<String> postJson(URI uri, String body) {
        return send(HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    static Map<String, Object> json(HttpResponse<String> response) {
        try {
            return MAPPER.readValue(response.body(), OBJECT);
        } catch (IOException e) {
            throw new AssertionError("Non-JSON response from " + response.uri() + ": " + response.body(), e);
        }
    }

    static String startOptimization(String prompt) {
        HttpResponse<String> response =
                postJson(BACKEND.resolve("/optimization/start"), "{\"customPrompt\":" + quote(prompt) + "}");
        if (response.statusCode() != 200) {
            throw new AssertionError("start returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return String.valueOf(json(response).get("requestId"));
    }

    static Progress progress(String requestId) {
        Map<String, Object> body = json(get(BACKEND.resolve("/optimization/progress/" + requestId)));
        return new Progress(
                String.valueOf(body.get("status")),
                stringOrNull(body.get("interactionUrl")),
                stringOrNull(body.get("interactionCode")),
                stringOrNull(body.get("error")));
    }

    static String report(String requestId) {
        HttpResponse<String> response = get(BACKEND.resolve("/optimization/results/" + requestId));
        return response.statusCode() == 200 ? stringOrNull(json(response).get("report")) : null;
    }

    /** Starts an optimization and polls to a terminal status, recording every status seen. */
    static RunResult runToCompletion(String prompt, Duration timeout) {
        String requestId = startOptimization(prompt);
        return pollToTerminal(requestId, timeout, new LinkedHashSet<>());
    }

    /**
     * Starts an optimization, waits for {@code interaction_required}, drives the Person Server's
     * consent REST API with the given decision, then polls to a terminal status.
     */
    static RunResult runWithConsent(String prompt, boolean approve, Duration timeout) {
        String requestId = startOptimization(prompt);
        long deadline = System.nanoTime() + timeout.toNanos();
        Progress interaction = null;
        Set<String> seen = new LinkedHashSet<>();
        while (System.nanoTime() < deadline) {
            Progress progress = progress(requestId);
            seen.add(progress.status());
            if ("interaction_required".equals(progress.status())) {
                interaction = progress;
                break;
            }
            if ("failed".equals(progress.status()) || "completed".equals(progress.status())) {
                return new RunResult(progress.status(), seen, report(requestId), progress.error());
            }
            sleep();
        }
        if (interaction == null) {
            throw new AssertionError("Never reached interaction_required; statuses seen: " + seen);
        }
        if (interaction.interactionUrl() == null || interaction.interactionCode() == null) {
            throw new AssertionError("interaction_required without url/code: " + interaction);
        }

        Map<String, Object> consent =
                json(get(PERSON_SERVER.resolve("/consent?code=" + interaction.interactionCode())));
        String pendingId = String.valueOf(consent.get("pending_id"));
        HttpResponse<String> decision = postJson(
                PERSON_SERVER.resolve("/consent/" + pendingId + "/decision"), "{\"approved\": " + approve + "}");
        if (decision.statusCode() != 200) {
            throw new AssertionError("Consent decision returned HTTP " + decision.statusCode());
        }

        return pollToTerminal(requestId, timeout, seen);
    }

    static String startMission(String description, String... products) {
        StringBuilder productArray = new StringBuilder("[");
        for (int i = 0; i < products.length; i++) {
            productArray.append(i == 0 ? "" : ",").append(quote(products[i]));
        }
        productArray.append("]");
        HttpResponse<String> response = postJson(
                BACKEND.resolve("/missions/start"),
                "{\"description\":" + quote(description) + ",\"products\":" + productArray + "}");
        if (response.statusCode() != 200) {
            throw new AssertionError("mission start returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return String.valueOf(json(response).get("missionId"));
    }

    @SuppressWarnings("unchecked")
    static MissionProgress missionProgress(String missionId) {
        Map<String, Object> body = json(get(BACKEND.resolve("/missions/progress/" + missionId)));
        return new MissionProgress(
                String.valueOf(body.get("status")),
                stringOrNull(body.get("missionS256")),
                stringOrNull(body.get("interactionUrl")),
                stringOrNull(body.get("interactionCode")),
                (java.util.List<Map<String, Object>>) body.get("steps"),
                stringOrNull(body.get("error")));
    }

    /**
     * Waits for the mission to ask for a user decision in the given status
     * ({@code awaiting_approval} or {@code interaction_required}), then decides via the PS
     * consent REST API. Matching on the expected status keeps the mission-approval ask and
     * the later per-step ask apart.
     */
    static MissionProgress decideMissionInteraction(
            String missionId, String expectedStatus, boolean approve, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            MissionProgress progress = missionProgress(missionId);
            String status = progress.status();
            if (expectedStatus.equals(status)) {
                decideConsent(progress.interactionCode(), approve);
                return progress;
            }
            if ("completed".equals(status) || "failed".equals(status)) {
                throw new AssertionError(
                        "Mission went terminal (" + status + ") before asking for a decision: " + progress);
            }
            sleep();
        }
        throw new AssertionError(
                "Mission " + missionId + " never reached " + expectedStatus + " within " + timeout.toSeconds() + "s");
    }

    static MissionProgress awaitMissionTerminal(String missionId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            MissionProgress progress = missionProgress(missionId);
            if ("completed".equals(progress.status()) || "failed".equals(progress.status())) {
                return progress;
            }
            sleep();
        }
        throw new AssertionError("Mission " + missionId + " not terminal within " + timeout.toSeconds() + "s");
    }

    static void decideConsent(String code, boolean approve) {
        if (code == null) {
            throw new AssertionError("No interaction code to decide on");
        }
        Map<String, Object> consent = json(get(PERSON_SERVER.resolve("/consent?code=" + code)));
        String pendingId = String.valueOf(consent.get("pending_id"));
        HttpResponse<String> decision = postJson(
                PERSON_SERVER.resolve("/consent/" + pendingId + "/decision"), "{\"approved\": " + approve + "}");
        if (decision.statusCode() != 200) {
            throw new AssertionError("Consent decision returned HTTP " + decision.statusCode());
        }
    }

    private static RunResult pollToTerminal(String requestId, Duration timeout, Set<String> seen) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Progress progress = progress(requestId);
            seen.add(progress.status());
            if ("completed".equals(progress.status()) || "failed".equals(progress.status())) {
                return new RunResult(progress.status(), seen, report(requestId), progress.error());
            }
            sleep();
        }
        throw new AssertionError("Optimization " + requestId + " not terminal within " + timeout.toSeconds()
                + "s; statuses seen: " + seen);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError(
                    "Request to " + request.uri() + " failed — are the services running " + "(scripts/run-demo.sh)? "
                            + e.getMessage(),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted calling " + request.uri(), e);
        }
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while polling", e);
        }
    }
}
