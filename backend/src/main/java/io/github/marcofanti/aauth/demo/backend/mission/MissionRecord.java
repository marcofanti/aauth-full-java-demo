package io.github.marcofanti.aauth.demo.backend.mission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Immutable state of one mission run; transitions produce new copies. */
public record MissionRecord(
        String missionId,
        String description,
        MissionStatus status,
        String missionS256,
        String interactionUrl,
        String interactionCode,
        List<MissionStep> steps,
        String error,
        Instant createdAt,
        Instant completedAt) {

    public static MissionRecord pending(String missionId, String description, Instant createdAt) {
        return new MissionRecord(
                missionId, description, MissionStatus.PENDING, null, null, null, List.of(), null, createdAt, null);
    }

    public MissionRecord awaitingApproval(String url, String code) {
        return new MissionRecord(
                missionId,
                description,
                MissionStatus.AWAITING_APPROVAL,
                missionS256,
                url,
                code,
                steps,
                error,
                createdAt,
                completedAt);
    }

    public MissionRecord running(String s256) {
        return new MissionRecord(
                missionId, description, MissionStatus.RUNNING, s256, null, null, steps, error, createdAt, completedAt);
    }

    public MissionRecord interactionRequired(String url, String code) {
        return new MissionRecord(
                missionId,
                description,
                MissionStatus.INTERACTION_REQUIRED,
                missionS256,
                url,
                code,
                steps,
                error,
                createdAt,
                completedAt);
    }

    public MissionRecord withStep(MissionStep step) {
        List<MissionStep> extended = new ArrayList<>(steps);
        extended.add(step);
        return new MissionRecord(
                missionId,
                description,
                MissionStatus.RUNNING,
                missionS256,
                null,
                null,
                List.copyOf(extended),
                error,
                createdAt,
                completedAt);
    }

    public MissionRecord completed(Instant at) {
        return new MissionRecord(
                missionId, description, MissionStatus.COMPLETED, missionS256, null, null, steps, null, createdAt, at);
    }

    public MissionRecord failed(String errorMessage) {
        return new MissionRecord(
                missionId,
                description,
                MissionStatus.FAILED,
                missionS256,
                interactionUrl,
                interactionCode,
                steps,
                errorMessage,
                createdAt,
                completedAt);
    }
}
