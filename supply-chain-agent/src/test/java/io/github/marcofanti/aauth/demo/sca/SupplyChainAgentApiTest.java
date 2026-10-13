package io.github.marcofanti.aauth.demo.sca;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcofanti.aauth.demo.a2a.A2aClient;
import io.github.marcofanti.aauth.demo.a2a.A2aJson;
import io.github.marcofanti.aauth.demo.a2a.AgentCard;
import io.github.marcofanti.aauth.demo.a2a.RequestSigner;
import io.github.marcofanti.aauth.demo.common.AAuthClientSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * The agent's identifier is {@code https://sca.example}; requests travel to localhost and are
 * signed for the identifier, as they would be behind the Caddy front. The market-analysis URL is
 * unreachable on purpose to cover the non-fatal fallback of the second hop.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(
        properties = {
            "server.port=19999",
            "demo.agent-url=https://sca.example/",
            "demo.market-analysis-url=http://127.0.0.1:1/",
            "demo.aauth.mode=hwk"
        })
class SupplyChainAgentApiTest {

    private static final URI BASE = URI.create("http://localhost:19999/");
    /** The agent's AAuth identifier; requests reach {@link #BASE} but are signed for this, as behind Caddy. */
    private static final URI IDENTIFIER = URI.create("https://sca.example/");

    private final AAuthClientSigner ephemeral = AAuthClientSigner.ephemeral();
    private final RequestSigner signer = (method, target, headers, body) ->
            ephemeral.sign(method, IDENTIFIER.resolve(target.getRawPath()), headers, body);

    @Test
    void answersSignedMessageSendWithOptimizationReport() throws Exception {
        String reply = new A2aClient(signer).sendText(BASE, "optimize laptop supply chain");

        assertThat(reply)
                .contains("# Supply Chain Optimization Report")
                .contains("Laptop fleet procurement")
                .doesNotContain("## Market Analysis");
    }

    @Test
    void marketAnalysisHopFailureProducesFallbackSection() throws Exception {
        String reply = new A2aClient(signer).sendText(BASE, "optimize laptops and perform market analysis");

        assertThat(reply).contains("## Market Analysis").contains("Market analysis unavailable");
    }

    @Test
    void rejectsUnsignedRequestWithChallenge() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(BASE)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Accept-Signature")).isPresent();
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
        assertThat(card.name()).isEqualTo("Supply Chain Agent");
        assertThat(card.skills()).extracting(skill -> skill.id()).contains("supply-chain-optimization");
    }
}
