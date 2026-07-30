package io.github.marcofanti.aauth.demo.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class HealthIT {

    @Test
    @Tag("core")
    void backendMetaEndpointsRespond() {
        assertThat(DemoApi.get(DemoApi.BACKEND.resolve("/health")).body()).contains("healthy");
        assertThat(DemoApi.get(DemoApi.BACKEND.resolve("/")).body()).contains("aauth-demo-backend");
        assertThat(DemoApi.json(DemoApi.get(DemoApi.BACKEND.resolve("/auth/me"))))
                .containsEntry("username", "guest");
        assertThat(DemoApi.get(DemoApi.BACKEND.resolve("/agents/status")).body())
                .contains("supply-chain-agent")
                .contains("market-analysis-agent");
    }

    @Test
    @Tag("core")
    void agentCardsAreServed() {
        Map<String, Object> sca =
                DemoApi.json(DemoApi.get(DemoApi.SUPPLY_CHAIN_AGENT.resolve("/.well-known/agent-card.json")));
        Map<String, Object> maa =
                DemoApi.json(DemoApi.get(DemoApi.MARKET_ANALYSIS_AGENT.resolve("/.well-known/agent-card.json")));

        assertThat(sca).containsEntry("name", "Supply Chain Agent");
        assertThat(maa).containsEntry("name", "Market Analysis Agent");
    }

    @Test
    @Tag("ps")
    void personServerMetadataIsServed() {
        Map<String, Object> metadata =
                DemoApi.json(DemoApi.get(DemoApi.PERSON_SERVER.resolve("/.well-known/aauth-agent.json")));

        assertThat(metadata).containsKey("issuer").containsKey("registration_endpoint");
    }

    @Test
    @Tag("ps")
    void agentsServeResourceMetadataAndJwks() {
        HttpResponse<String> metadata =
                DemoApi.get(DemoApi.SUPPLY_CHAIN_AGENT.resolve("/.well-known/aauth-resource.json"));
        HttpResponse<String> jwks = DemoApi.get(DemoApi.SUPPLY_CHAIN_AGENT.resolve("/.well-known/jwks.json"));

        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(metadata.body()).contains("jwks_uri");
        assertThat(jwks.statusCode()).isEqualTo(200);
        assertThat(jwks.body()).contains("\"keys\"");
    }
}
