package io.github.marcofanti.aauth.demo.maa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcofanti.aauth.demo.a2a.A2aClient;
import io.github.marcofanti.aauth.demo.a2a.RequestSigner;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** With {@code demo.aauth.mode=off} the endpoint accepts unsigned requests (original mode0). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "demo.aauth.mode=off")
class MarketAnalysisAgentOffModeTest {

    @LocalServerPort
    private int port;

    @Test
    void acceptsUnsignedRequests() throws Exception {
        String reply = new A2aClient(RequestSigner.none())
                .sendText(URI.create("http://localhost:" + port + "/"), "demand this quarter");

        assertThat(reply).contains("Laptop Demand");
    }
}
