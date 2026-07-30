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

/** Thin client for the live demo services under test (started by scripts/run-demo.sh). */
final class DemoApi {

    static final URI BACKEND = URI.create("http://portal.uma.lab:8000");
    static final URI SUPPLY_CHAIN_AGENT = URI.create("http://gateway.uma.lab:9999/");
    static final URI MARKET_ANALYSIS_AGENT = URI.create("http://gateway.uma.lab:9998/");
    static final URI PERSON_SERVER = URI.create("http://ps.uma.lab:8765");

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
