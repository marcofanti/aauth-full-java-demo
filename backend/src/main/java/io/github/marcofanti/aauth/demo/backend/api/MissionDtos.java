package io.github.marcofanti.aauth.demo.backend.api;

import io.github.marcofanti.aauth.demo.backend.mission.MissionRecord;
import io.github.marcofanti.aauth.demo.backend.mission.MissionStep;
import java.util.List;

/** REST wire shapes for the mission API. */
public final class MissionDtos {

    private MissionDtos() {}

    public record StartRequest(String description, List<String> products) {}

    public record StartResponse(String missionId, String status) {}

    public record StepResponse(String action, String detail, String outcome) {

        static StepResponse from(MissionStep step) {
            return new StepResponse(step.action(), step.detail(), step.outcome());
        }
    }

    public record ProgressResponse(
            String missionId,
            String description,
            String status,
            String missionS256,
            String interactionUrl,
            String interactionCode,
            List<StepResponse> steps,
            String error) {

        static ProgressResponse from(MissionRecord record) {
            return new ProgressResponse(
                    record.missionId(),
                    record.description(),
                    record.status().wireName(),
                    record.missionS256(),
                    record.interactionUrl(),
                    record.interactionCode(),
                    record.steps().stream().map(StepResponse::from).toList(),
                    record.error());
        }
    }
}
