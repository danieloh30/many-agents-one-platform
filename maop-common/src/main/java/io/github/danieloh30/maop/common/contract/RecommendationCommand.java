package io.github.danieloh30.maop.common.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecommendationCommand(UUID runId, UUID taskId, Incident incident, Classification classification, List<EvidenceItem> evidence, EvidenceCompleteness evidenceCompleteness, RemediationMode remediationMode, TaskBudget budget, Instant deadline) {
}
