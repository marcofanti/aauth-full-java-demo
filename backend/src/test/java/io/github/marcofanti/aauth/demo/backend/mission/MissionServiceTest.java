package io.github.marcofanti.aauth.demo.backend.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.marcofanti.aauth.demo.backend.activity.ActivityService;
import io.github.marcofanti.aauth.demo.common.MissionClient;
import io.github.marcofanti.aauth.demo.common.MissionException;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MissionServiceTest {

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

    private static final MissionClient.Mission MISSION = new MissionClient.Mission("aauth:alice@ps.test", "abc123");

    private final MissionClient missions = mock(MissionClient.class);
    private final ExecutorService executor = new DirectExecutor();
    private final ActivityService activities = new ActivityService();

    private MissionService service(String report) {
        return new MissionService(missions, (prompt, onInteraction) -> report, activities, executor);
    }

    @Test
    void missionRunsStepsAndRecordsOutcomes() {
        when(missions.propose(anyString(), anyMap(), any())).thenReturn(MISSION);
        when(missions.permission(eq(MISSION), eq(MissionService.OPTIMIZE_ACTION), anyString(), anyMap(), any()))
                .thenReturn(MissionClient.Permission.GRANTED);
        when(missions.permission(eq(MISSION), eq(MissionService.PURCHASE_ACTION), anyString(), anyMap(), any()))
                .thenReturn(MissionClient.Permission.DENIED);
        MissionService service = service("optimized fine");

        String missionId = service.start("Optimize the laptop line", List.of("laptop"));

        MissionRecord record = service.get(missionId).orElseThrow();
        assertThat(record.status()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(record.missionS256()).isEqualTo("abc123");
        assertThat(record.steps())
                .containsExactly(
                        new MissionStep(MissionService.OPTIMIZE_ACTION, "laptop", "granted"),
                        new MissionStep(MissionService.OPTIMIZE_ACTION, "laptop", "completed"),
                        new MissionStep(MissionService.PURCHASE_ACTION, "laptop", "denied"));
        verify(missions).audit(eq(MISSION), eq(MissionService.OPTIMIZE_ACTION), anyString(), anyMap());
    }

    @Test
    void approvedPurchaseIsAudited() {
        when(missions.propose(anyString(), anyMap(), any())).thenReturn(MISSION);
        when(missions.permission(any(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(MissionClient.Permission.GRANTED);
        MissionService service = service("report");

        String missionId = service.start(null, null);

        MissionRecord record = service.get(missionId).orElseThrow();
        assertThat(record.status()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(record.description()).isEqualTo(MissionService.DEFAULT_DESCRIPTION);
        // Two default products optimized, then the purchase — all granted.
        assertThat(record.steps())
                .extracting(MissionStep::outcome)
                .containsExactly("granted", "completed", "granted", "completed", "granted");
        verify(missions).audit(eq(MISSION), eq(MissionService.PURCHASE_ACTION), anyString(), anyMap());
    }

    @Test
    void deniedOptimizePermissionSkipsTheAgentCall() {
        when(missions.propose(anyString(), anyMap(), any())).thenReturn(MISSION);
        when(missions.permission(eq(MISSION), eq(MissionService.OPTIMIZE_ACTION), anyString(), anyMap(), any()))
                .thenReturn(MissionClient.Permission.DENIED);
        when(missions.permission(eq(MISSION), eq(MissionService.PURCHASE_ACTION), anyString(), anyMap(), any()))
                .thenReturn(MissionClient.Permission.DENIED);
        MissionService service = new MissionService(
                missions,
                (prompt, onInteraction) -> {
                    throw new AssertionError("agent chain must not run for a denied step");
                },
                activities,
                executor);

        String missionId = service.start("desc", List.of("laptop"));

        MissionRecord record = service.get(missionId).orElseThrow();
        assertThat(record.status()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(record.steps())
                .containsExactly(
                        new MissionStep(MissionService.OPTIMIZE_ACTION, "laptop", "denied"),
                        new MissionStep(MissionService.PURCHASE_ACTION, "laptop", "denied"));
    }

    @Test
    void deniedMissionApprovalFailsTheRun() {
        when(missions.propose(anyString(), anyMap(), any()))
                .thenThrow(new MissionException("The mission approval request was denied"));
        MissionService service = service("unused");

        String missionId = service.start("desc", List.of("laptop"));

        MissionRecord record = service.get(missionId).orElseThrow();
        assertThat(record.status()).isEqualTo(MissionStatus.FAILED);
        assertThat(record.error()).contains("denied");
        assertThat(service.all()).hasSize(1);
    }
}
