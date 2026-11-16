package io.github.danieloh30.maop.common.model;

public enum Outcome {
    NO_ACTION_REQUIRED,
    ACTION_REJECTED,
    APPROVAL_EXPIRED,
    REMEDIATION_SIMULATED,
    EVIDENCE_ONLY,
    DEADLINE_EXCEEDED,
    BUDGET_EXHAUSTED,
    MODEL_OUTPUT_INVALID,
    MODEL_UNAVAILABLE,
    TOOL_DENIED,
    TOOL_UNAVAILABLE,
    INVALID_COMMAND,
    DEAD_LETTERED,
    QUORUM_NOT_MET,
    CANCELLED_BY_OPERATOR,
    INTERNAL;

    public static Outcome from(FailureReason reason) {
        return Outcome.valueOf(reason.name());
    }
}
