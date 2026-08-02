package io.github.marcofanti.aauth.demo.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The mission flow (missions mode only): the backend records a mission at the Person Server,
 * optimize steps auto-grant against the mission's approved tools and run the full A2A chain
 * with no prompts, and the closing purchase step — outside the approved tools — defers to
 * the user, whose decision is the result either way.
 */
@Tag("missions")
class MissionFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(150);

    @Test
    void inScopeStepsAutoGrantAndTheDeniedPurchaseStillCompletesTheMission() {
        String missionId = DemoApi.startMission("Optimize the laptop supply chain and report findings.", "laptop");

        // No prompt for the optimize step; the only user ask is the out-of-scope purchase.
        DemoApi.MissionProgress purchaseAsk =
                DemoApi.decideMissionInteraction(missionId, "interaction_required", false, TIMEOUT);
        assertThat(purchaseAsk.interactionUrl()).isNotNull();
        assertThat(purchaseAsk.interactionCode()).isNotNull();

        DemoApi.MissionProgress terminal = DemoApi.awaitMissionTerminal(missionId, TIMEOUT);
        assertThat(terminal.status()).isEqualTo("completed");
        assertThat(terminal.missionS256()).isNotNull();

        List<Map<String, Object>> steps = terminal.steps();
        assertThat(steps).isNotNull();
        assertThat(steps.stream().map(step -> step.get("action") + "=" + step.get("outcome")))
                .containsExactly(
                        "supply-chain:optimize=granted",
                        "supply-chain:optimize=completed",
                        "inventory:purchase=denied");
    }

    @Test
    void approvedPurchaseEndsTheMissionWithAllStepsGranted() {
        String missionId = DemoApi.startMission("Optimize the monitor supply chain.", "monitor");

        DemoApi.decideMissionInteraction(missionId, "interaction_required", true, TIMEOUT);

        DemoApi.MissionProgress terminal = DemoApi.awaitMissionTerminal(missionId, TIMEOUT);
        assertThat(terminal.status()).isEqualTo("completed");
        assertThat(terminal.steps().stream().map(step -> step.get("action") + "=" + step.get("outcome")))
                .containsExactly(
                        "supply-chain:optimize=granted",
                        "supply-chain:optimize=completed",
                        "inventory:purchase=granted");
    }

    @Test
    void missionApiIsReachable() {
        assertThat(DemoApi.get(DemoApi.BACKEND.resolve("/missions/all")).statusCode())
                .isEqualTo(200);
    }
}
