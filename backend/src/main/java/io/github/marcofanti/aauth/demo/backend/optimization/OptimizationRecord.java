package io.github.marcofanti.aauth.demo.backend.optimization;

import java.time.Instant;

/** Immutable state of one optimization request; transitions produce new copies. */
public record OptimizationRecord(
        String requestId,
        String prompt,
        OptimizationStatus status,
        String report,
        String error,
        String interactionUrl,
        String interactionCode,
        Instant createdAt,
        Instant completedAt) {

    public static OptimizationRecord pending(String requestId, String prompt, Instant createdAt) {
        return new OptimizationRecord(
                requestId, prompt, OptimizationStatus.PENDING, null, null, null, null, createdAt, null);
    }

    public OptimizationRecord withStatus(OptimizationStatus newStatus) {
        return new OptimizationRecord(
                requestId, prompt, newStatus, report, error, interactionUrl, interactionCode, createdAt, completedAt);
    }

    public OptimizationRecord completed(String finalReport, Instant at) {
        return new OptimizationRecord(
                requestId,
                prompt,
                OptimizationStatus.COMPLETED,
                finalReport,
                null,
                interactionUrl,
                interactionCode,
                createdAt,
                at);
    }

    public OptimizationRecord failed(String errorMessage) {
        return new OptimizationRecord(
                requestId,
                prompt,
                OptimizationStatus.FAILED,
                report,
                errorMessage,
                interactionUrl,
                interactionCode,
                createdAt,
                completedAt);
    }
}
