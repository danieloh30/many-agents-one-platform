package io.github.danieloh30.maop.common.model;

public enum TaskState {
    DISPATCHED,
    COMPLETED,
    FAILED,
    DEAD_LETTERED,
    EXPIRED,
    CANCELLED;

    public boolean terminal() {
        return this != DISPATCHED;
    }
}
