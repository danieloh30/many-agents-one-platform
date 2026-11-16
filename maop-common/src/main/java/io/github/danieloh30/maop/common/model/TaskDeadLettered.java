package io.github.danieloh30.maop.common.model;

import java.util.UUID;

public record TaskDeadLettered(UUID taskId, TaskKind kind, String reason, long deliveries, String dlqSubject) {
}
