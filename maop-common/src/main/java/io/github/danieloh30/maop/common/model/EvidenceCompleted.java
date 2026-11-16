package io.github.danieloh30.maop.common.model;

import java.util.UUID;

public record EvidenceCompleted(UUID taskId, EvidenceItem evidence, Usage usage, long deliveries) {
}
