package io.github.marcofanti.aauth.demo.backend.api;

import io.github.marcofanti.aauth.demo.backend.optimization.OptimizationRecord;
import java.time.Instant;
import java.util.Map;

/** REST wire types for the optimization API. */
public final class OptimizationDtos {

    private OptimizationDtos() {}

    public record StartRequest(String scenario, String customPrompt, Map<String, Object> constraints) {}

    public record StartResponse(String requestId, String status) {}

    public record ProgressResponse(
            String requestId, String status, String interactionUrl, String interactionCode, String error) {

        public static ProgressResponse from(OptimizationRecord record) {
            return new ProgressResponse(
                    record.requestId(),
                    record.status().wireName(),
                    record.interactionUrl(),
                    record.interactionCode(),
                    record.error());
        }
    }

    public record ResultsResponse(String requestId, String status, String report, Instant completedAt) {

        public static ResultsResponse from(OptimizationRecord record) {
            return new ResultsResponse(
                    record.requestId(), record.status().wireName(), record.report(), record.completedAt());
        }
    }
}
