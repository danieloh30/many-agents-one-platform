package io.github.danieloh30.maop.common;

import io.github.danieloh30.maop.common.contract.FailureReason;

/** A classified task failure. Retryable failures are negatively acknowledged; others end the task. */
public class TaskFailure extends RuntimeException {

    private final FailureReason reason;
    private final boolean retryable;

    public TaskFailure(FailureReason reason, boolean retryable, String message) {
        super(message);
        this.reason = reason;
        this.retryable = retryable;
    }

    public TaskFailure(FailureReason reason, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    public FailureReason reason() {
        return reason;
    }

    public boolean retryable() {
        return retryable;
    }

    public static TaskFailure deadline(String what) {
        return new TaskFailure(FailureReason.DEADLINE_EXCEEDED, false, "Deadline reached before " + what);
    }

    public static TaskFailure budget(String what) {
        return new TaskFailure(FailureReason.BUDGET_EXHAUSTED, false, "Budget exhausted: " + what);
    }
}
