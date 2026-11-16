package io.github.danieloh30.maop.common;

import java.util.UUID;

/** The run/task being processed on the current thread (for observation events emitted from shared code). */
public final class RunContext {

    public record Current(UUID runId, UUID taskId) {
    }

    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

    private RunContext() {
    }

    public static void set(UUID runId, UUID taskId) {
        CURRENT.set(new Current(runId, taskId));
    }

    public static Current get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
