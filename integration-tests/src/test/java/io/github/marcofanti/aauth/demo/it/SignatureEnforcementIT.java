package io.github.marcofanti.aauth.demo.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** In every signed mode, unsigned A2A requests must be rejected with an AAuth challenge. */
@Tag("signed")
class SignatureEnforcementIT {

    private static void assertRejected(URI agent) {
        HttpResponse<String> response = DemoApi.postJson(agent, "{\"jsonrpc\":\"2.0\"}");

        assertThat(response.statusCode()).isEqualTo(401);
        boolean challenged = response.headers().firstValue("Accept-Signature").isPresent()
                || response.headers().firstValue("AAuth-Requirement").isPresent();
        assertThat(challenged).as("401 must carry an AAuth challenge header").isTrue();
    }

    @Test
    void supplyChainAgentRejectsUnsignedRequests() {
        assertRejected(DemoApi.SUPPLY_CHAIN_AGENT);
    }

    @Test
    void marketAnalysisAgentRejectsUnsignedRequests() {
        assertRejected(DemoApi.MARKET_ANALYSIS_AGENT);
    }

    @Test
    void agentCardStaysPublic() {
        assertThat(DemoApi.get(DemoApi.SUPPLY_CHAIN_AGENT.resolve("/.well-known/agent-card.json"))
                        .statusCode())
                .isEqualTo(200);
    }
}
