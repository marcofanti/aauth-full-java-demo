package io.github.marcofanti.aauth.demo.maa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcofanti.aauth.demo.a2a.A2aClient;
import io.github.marcofanti.aauth.demo.a2a.A2aJson;
import io.github.marcofanti.aauth.demo.a2a.AgentCard;
import io.github.marcofanti.aauth.demo.common.AAuthClientSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs on a fixed port so the AAuth canonical authority ({@code localhost:19998},
 * resolving to 127.0.0.1) matches what clients sign.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(properties = {"server.port=19998", "demo.agent-url=http://localhost:19998/", "demo.aauth.mode=hwk"})
class MarketAnalysisAgentApiTest {

    private static final URI BASE = URI.create("http://localhost:19998/");

    private final AAuthClientSigner signer = AAuthClientSigner.ephemeral();

    @Test
    void answersSignedMessageSendWithMarkdownReport() throws Exception {
        String reply = new A2aClient(signer).sendText(BASE, "comprehensive market analysis");

        assertThat(reply).contains("# Market Analysis Report").contains("Market Trends");
    }

    @Test
    void rejectsUnsignedRequestWithChallenge() throws Exception {
        HttpResponse<String> response = post(BASE, "{\"jsonrpc\":\"2.0\"}", Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Accept-Signature")).isPresent();
        assertThat(response.body()).contains("unauthorized");
    }

    @Test
    void servesAgentCardWithoutSignature() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(BASE.resolve(AgentCard.WELL_KNOWN_PATH))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        AgentCard card = A2aJson.parse(response.body(), AgentCard.class);
        assertThat(card.name()).isEqualTo("Market Analysis Agent");
        assertThat(card.url()).isEqualTo("http://localhost:19998/");
    }

    @Test
    void rejectsUnknownJsonRpcMethodOnSignedRequest() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"message/stream\",\"params\":{}}";
        HttpResponse<String> response = postSigned(body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("-32601");
    }

    @Test
    void rejectsMalformedJsonOnSignedRequest() throws Exception {
        HttpResponse<String> response = postSigned("not json at all");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("-32600");
    }

    private HttpResponse<String> postSigned(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signer.sign("POST", BASE, Map.of("Content-Type", "application/json"), bytes);
        return post(BASE, body, headers);
    }

    private static HttpResponse<String> post(URI uri, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body));
        if (!headers.containsKey("Content-Type")) {
            builder.header("Content-Type", "application/json");
        }
        headers.forEach(builder::header);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
