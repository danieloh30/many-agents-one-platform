package io.github.danieloh30.maop.common.model;

import java.util.UUID;

public record RecommendationCompleted(UUID taskId, Recommendation recommendation, ExecutionAttempt executionAttempt, Usage usage, long deliveries) {
}
