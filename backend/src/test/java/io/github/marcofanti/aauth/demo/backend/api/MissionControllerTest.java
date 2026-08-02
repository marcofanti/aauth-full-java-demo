package io.github.marcofanti.aauth.demo.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.marcofanti.aauth.demo.backend.activity.ActivityService;
import io.github.marcofanti.aauth.demo.backend.api.MissionDtos.ProgressResponse;
import io.github.marcofanti.aauth.demo.backend.api.MissionDtos.StartRequest;
import io.github.marcofanti.aauth.demo.backend.api.MissionDtos.StartResponse;
import io.github.marcofanti.aauth.demo.backend.mission.MissionService;
import io.github.marcofanti.aauth.demo.common.MissionClient;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class MissionControllerTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private static MissionController controller(MissionService service) {
        ObjectProvider<MissionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return new MissionController(provider);
    }

    private MissionService liveService() {
        MissionClient missions = mock(MissionClient.class);
        when(missions.propose(anyString(), anyMap(), any()))
                .thenReturn(new MissionClient.Mission("aauth:alice@ps.test", "abc123"));
        when(missions.permission(any(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(MissionClient.Permission.GRANTED);
        return new MissionService(missions, (prompt, onInteraction) -> "report", new ActivityService(), executor);
    }

    @Test
    void lowerModesGet503WithGuidance() {
        MissionController controller = controller(null);

        assertThat(controller.start(null).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(controller.progress("any").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(controller.all().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void startProgressAndAllRoundTrip() {
        MissionController controller = controller(liveService());

        ResponseEntity<Object> started = controller.start(new StartRequest("Optimize laptops", List.of("laptop")));
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        String missionId = ((StartResponse) started.getBody()).missionId();

        awaitTerminal(controller, missionId);
        ProgressResponse progress =
                (ProgressResponse) controller.progress(missionId).getBody();
        assertThat(progress.description()).isEqualTo("Optimize laptops");
        assertThat(progress.missionS256()).isEqualTo("abc123");
        assertThat(progress.steps())
                .extracting(MissionDtos.StepResponse::action)
                .contains("inventory:purchase");

        assertThat(controller.progress("unknown").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        @SuppressWarnings("unchecked")
        List<ProgressResponse> all = (List<ProgressResponse>) controller.all().getBody();
        assertThat(all).hasSize(1);
    }

    private static void awaitTerminal(MissionController controller, String missionId) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            ProgressResponse progress =
                    (ProgressResponse) controller.progress(missionId).getBody();
            if ("completed".equals(progress.status()) || "failed".equals(progress.status())) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Mission " + missionId + " not terminal in time");
    }
}
