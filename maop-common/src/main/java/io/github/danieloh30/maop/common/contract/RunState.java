package io.github.danieloh30.maop.common.contract;

public enum RunState {
    SUBMITTED,
    TRIAGING,
    GATHERING_EVIDENCE,
    RECOMMENDING,
    AWAITING_APPROVAL,
    EXECUTING_ACTION,
    COMPLETED,
    NEEDS_REVIEW,
    TIMED_OUT,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return switch (this) {
            case COMPLETED, NEEDS_REVIEW, TIMED_OUT, FAILED, CANCELLED -> true;
            default -> false;
        };
    }
}
