package io.github.marcofanti.aauth.demo.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.marcofanti.aauth.demo.backend.optimization.SupplyChainGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendApiTest {

    @TestConfiguration
    static class StubGateway {
        @Bean
        @Primary
        SupplyChainGateway stubSupplyChainGateway() {
            return prompt -> "# Supply Chain Optimization Report\n\nstubbed for: " + prompt;
        }
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAndMetaEndpointsRespond() throws Exception {
        assertThat(get("/health").body()).contains("healthy");
        assertThat(get("/").body()).contains("aauth-demo-backend");
        assertThat(get("/auth/me").body()).contains("guest");
    }

    @Test
    void agentsStatusListsConfiguredAgents() throws Exception {
        String body = get("/agents/status").body();

        assertThat(body).contains("supply-chain-agent").contains("market-analysis-agent");
    }

    @Test
    void optimizationLifecycleCompletesAndServesResults() throws Exception {
        HttpResponse<String> started =
                post("/optimization/start", "{\"customPrompt\":\"optimize laptop supply chain\"}");
        assertThat(started.statusCode()).isEqualTo(200);
        String requestId = extract(started.body(), "requestId");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                        get("/optimization/progress/" + requestId).body())
                .contains("\"completed\""));

        HttpResponse<String> results = get("/optimization/results/" + requestId);
        assertThat(results.statusCode()).isEqualTo(200);
        assertThat(results.body()).contains("stubbed for: optimize laptop supply chain");

        assertThat(get("/optimization/all").body()).contains(requestId);
        assertThat(get("/agents/activities").body()).contains("Optimization accepted");
    }

    @Test
    void unknownRequestIdReturns404() throws Exception {
        assertThat(get("/optimization/progress/nope").statusCode()).isEqualTo(404);
        assertThat(get("/optimization/results/nope").statusCode()).isEqualTo(404);
    }

    @Test
    void resultsBeforeCompletionConflictOrComplete() throws Exception {
        HttpResponse<String> started = post("/optimization/start", "{}");
        String requestId = extract(started.body(), "requestId");

        int status = get("/optimization/results/" + requestId).statusCode();
        // Timing-dependent: 409 while running, 200 once the stub gateway returned.
        assertThat(status).isIn(200, 409);
    }

    private static String extract(String json, String field) {
        int start = json.indexOf(field) + field.length() + 3;
        return json.substring(start, json.indexOf('"', start));
    }
}
