package io.github.marcofanti.aauth.demo.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The user-consent flow (consent mode only). Ordered: denial first — after an approval the
 * backend caches the auth token and later runs no longer prompt (correct AAuth semantics).
 */
@Tag("consent")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsentIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(150);

    @Test
    @Order(1)
    void deniedConsentFailsTheRun() {
        DemoApi.RunResult result = DemoApi.runWithConsent("optimize laptop inventory", false, TIMEOUT);

        assertThat(result.finalStatus()).isEqualTo("failed");
        assertThat(result.error()).containsIgnoringCase("denied");
    }

    @Test
    @Order(2)
    void approvedConsentCompletesTheRun() {
        DemoApi.RunResult result = DemoApi.runWithConsent("optimize laptop supply chain", true, TIMEOUT);

        assertThat(result.statusesSeen()).contains("interaction_required");
        assertThat(result.finalStatus()).isEqualTo("completed");
        assertThat(result.report()).contains("# Supply Chain Optimization Report");
    }

    @Test
    @Order(3)
    void cachedAuthTokenSkipsConsentOnSubsequentRuns() {
        DemoApi.RunResult result = DemoApi.runToCompletion("optimize laptops again", TIMEOUT);

        assertThat(result.finalStatus()).isEqualTo("completed");
        assertThat(result.statusesSeen()).doesNotContain("interaction_required");
    }
}
