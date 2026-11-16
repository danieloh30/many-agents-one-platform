package io.github.danieloh30.maop.common.model;

public record Budget(int maxEvidenceTasks, int maxModelCalls, int maxToolCallsPerTask, int maxTotalTokens) {

    public static Budget defaults() {
        return new Budget(4, 12, 4, 24_000);
    }

    /** Clamp each limit to the hard caps from architecture.md section 4.3. */
    public Budget clamped() {
        return new Budget(Math.clamp(maxEvidenceTasks, 1, 6), Math.clamp(maxModelCalls, 1, 20),
                Math.clamp(maxToolCallsPerTask, 1, 6), Math.clamp(maxTotalTokens, 1_000, 40_000));
    }
}
