package io.github.danieloh30.maop.common.contract;

import java.time.Instant;
import java.util.UUID;

public record ActionCommand(UUID runId, UUID taskId, UUID approvalId, ProposedAction proposedAction, TaskBudget budget, Instant deadline) {
}
