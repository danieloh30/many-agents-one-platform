package io.github.danieloh30.maop.common.model;

import java.util.UUID;

public record ActionCompleted(UUID taskId, String invocationId, String status, boolean replayed, Usage usage, long deliveries) {
}
