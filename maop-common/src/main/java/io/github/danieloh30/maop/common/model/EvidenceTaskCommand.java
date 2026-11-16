package io.github.danieloh30.maop.common.model;

import java.time.Instant;
import java.util.UUID;

public record EvidenceTaskCommand(UUID runId, UUID taskId, Topic topic, Incident incident, String question, TaskBudget budget, Instant deadline, FaultInjection faultInjection, String scenario) {
}
