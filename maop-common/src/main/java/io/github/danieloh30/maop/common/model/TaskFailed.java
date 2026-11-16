package io.github.danieloh30.maop.common.model;

import java.util.UUID;

public record TaskFailed(UUID taskId, TaskKind kind, FailureReason reason, boolean retryable, String detail, long deliveries, Usage usage) {
}
