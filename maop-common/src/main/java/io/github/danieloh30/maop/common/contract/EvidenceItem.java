package io.github.danieloh30.maop.common.contract;

import java.util.List;
import java.util.UUID;

public record EvidenceItem(String evidenceId, UUID taskId, Topic topic, String finding, double confidence, List<Fact> facts, String dataOrigin, ModelInfo model) {
}
