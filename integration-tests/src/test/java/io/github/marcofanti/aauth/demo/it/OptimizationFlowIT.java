package io.github.marcofanti.aauth.demo.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end flows for the non-consent modes (off/hwk/jwt/auth-token): every run must complete
 * without ever surfacing {@code interaction_required}.
 */
@Tag("core")
class OptimizationFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Test
    void happyPathCompletesWithReport() {
        DemoApi.RunResult result = DemoApi.runToCompletion("optimize laptop supply chain", TIMEOUT);

        assertThat(result.finalStatus()).isEqualTo("completed");
        assertThat(result.report()).contains("# Supply Chain Optimization Report");
        assertThat(result.statusesSeen()).doesNotContain("interaction_required");
    }

    @Test
    void marketAnalysisHopCompletes() {
        DemoApi.RunResult result = DemoApi.runToCompletion("optimize laptops and perform market analysis", TIMEOUT);

        assertThat(result.finalStatus()).isEqualTo("completed");
        assertThat(result.report()).contains("## Market Analysis").doesNotContain("unavailable");
        assertThat(result.statusesSeen()).doesNotContain("interaction_required");
    }

    @Test
    void concurrentRequestsAllComplete() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            List<Future<DemoApi.RunResult>> futures = List.of(
                    pool.submit(() -> DemoApi.runToCompletion("optimize laptops for cost", TIMEOUT)),
                    pool.submit(() -> DemoApi.runToCompletion("urgent laptop order", TIMEOUT)),
                    pool.submit(() -> DemoApi.runToCompletion("check laptop inventory", TIMEOUT)));
            for (Future<DemoApi.RunResult> future : futures) {
                assertThat(future.get().finalStatus()).isEqualTo("completed");
            }
        }
    }
}
