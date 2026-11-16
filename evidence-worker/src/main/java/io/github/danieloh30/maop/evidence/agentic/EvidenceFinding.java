package io.github.danieloh30.maop.evidence.agentic;

import io.github.danieloh30.maop.common.model.Fact;
import java.util.List;

/** Typed output of the evidence agent, validated against the invocations actually returned to the task. */
public record EvidenceFinding(String finding, double confidence, List<Fact> facts) {
}
