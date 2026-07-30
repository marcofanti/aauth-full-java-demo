package io.github.marcofanti.aauth.demo.backend.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcofanti.aauth.demo.a2a.A2aClientException;
import io.github.marcofanti.aauth.demo.backend.activity.ActivityService;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OptimizationServiceTest {

    /** Runs submitted tasks synchronously so state transitions are deterministic in tests. */
    private static final class DirectExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private final ExecutorService directExecutor = new DirectExecutor();
    private final ActivityService activities = new ActivityService();

    @Test
    void successfulRunCompletesWithReport() {
        OptimizationService service = new OptimizationService(
                (prompt, onInteraction) -> "# Report for " + prompt, activities, directExecutor);

        String requestId = service.start("optimize laptops");

        OptimizationRecord record = service.get(requestId).orElseThrow();
        assertThat(record.status()).isEqualTo(OptimizationStatus.COMPLETED);
        assertThat(record.report()).isEqualTo("# Report for optimize laptops");
        assertThat(record.completedAt()).isNotNull();
        assertThat(record.error()).isNull();
    }

    @Test
    void gatewayFailureEndsInFailedWithError() {
        OptimizationService service = new OptimizationService(
                (prompt, onInteraction) -> {
                    throw new A2aClientException("agent unreachable");
                },
                activities,
                directExecutor);

        String requestId = service.start("optimize laptops");

        OptimizationRecord record = service.get(requestId).orElseThrow();
        assertThat(record.status()).isEqualTo(OptimizationStatus.FAILED);
        assertThat(record.error()).isEqualTo("agent unreachable");
        assertThat(record.report()).isNull();
    }

    @Test
    void blankPromptFallsBackToDefault() {
        OptimizationService service =
                new OptimizationService((prompt, onInteraction) -> prompt, activities, directExecutor);

        String requestId = service.start("  ");

        assertThat(service.get(requestId).orElseThrow().report()).isEqualTo(OptimizationService.DEFAULT_PROMPT);
    }

    @Test
    void unknownRequestIdIsEmpty() {
        OptimizationService service =
                new OptimizationService((prompt, onInteraction) -> prompt, activities, directExecutor);

        assertThat(service.get("nope")).isEmpty();
    }

    @Test
    void allListsNewestFirstAndClearEmpties() {
        OptimizationService service =
                new OptimizationService((prompt, onInteraction) -> prompt, activities, directExecutor);
        service.start("first");
        service.start("second");

        assertThat(service.all()).hasSize(2);

        service.clear();

        assertThat(service.all()).isEmpty();
    }

    @Test
    void activitiesAreRecordedForRuns() {
        OptimizationService service =
                new OptimizationService((prompt, onInteraction) -> "ok", activities, directExecutor);

        service.start("optimize laptops");

        assertThat(activities.list(10))
                .anySatisfy(entry -> assertThat(entry.message()).contains("Optimization accepted"))
                .anySatisfy(entry -> assertThat(entry.message()).contains("completed"));
    }
}
