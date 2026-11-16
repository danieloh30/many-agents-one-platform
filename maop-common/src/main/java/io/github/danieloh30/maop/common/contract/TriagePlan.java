package io.github.danieloh30.maop.common.contract;

import java.util.List;

/** Structured triage output: classification plus the evidence tasks to delegate. */
public record TriagePlan(Category category, Severity severity, double confidence, String rationale,
        List<PlannedTask> tasks) {

    public record PlannedTask(Topic topic, String question) {
    }

    public Classification classification() {
        return new Classification(category, severity, confidence, rationale);
    }
}
