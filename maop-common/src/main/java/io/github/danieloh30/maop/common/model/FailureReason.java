package io.github.danieloh30.maop.common.model;

public enum FailureReason {
    DEADLINE_EXCEEDED,
    BUDGET_EXHAUSTED,
    MODEL_OUTPUT_INVALID,
    MODEL_UNAVAILABLE,
    TOOL_DENIED,
    TOOL_UNAVAILABLE,
    INVALID_COMMAND,
    INTERNAL
}
