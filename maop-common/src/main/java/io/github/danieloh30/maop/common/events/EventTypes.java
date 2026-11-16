package io.github.danieloh30.maop.common.events;

/** CloudEvents type suffixes (contracts.md section 3.4 and 5.3). The full type is {@link #PREFIX} + suffix. */
public final class EventTypes {

    public static final String PREFIX = "io.github.danieloh30.maop.";

    // Commands (MAOP_WORK)
    public static final String RUN_TRIAGE_REQUESTED = "run.triage.requested.v1";
    public static final String EVIDENCE_REQUESTED = "task.evidence.requested.v1";
    public static final String RECOMMENDATION_REQUESTED = "task.recommendation.requested.v1";
    public static final String ACTION_REQUESTED = "task.action.requested.v1";

    // Facts consumed by triage
    public static final String EVIDENCE_COMPLETED = "task.evidence.completed.v1";
    public static final String RECOMMENDATION_COMPLETED = "task.recommendation.completed.v1";
    public static final String ACTION_COMPLETED = "task.action.completed.v1";
    public static final String TASK_FAILED = "task.failed.v1";
    public static final String TASK_DEAD_LETTERED = "task.dead_lettered.v1";

    // Observation events
    public static final String RUN_STATE_CHANGED = "run.state_changed.v1";
    public static final String TASK_DISPATCHED = "task.dispatched.v1";
    public static final String TASK_STARTED = "task.started.v1";
    public static final String MODEL_CALL_COMPLETED = "model.call.completed.v1";
    public static final String TOOL_INVOCATION_COMPLETED = "tool.invocation.completed.v1";
    public static final String WORKER_HEARTBEAT = "worker.heartbeat.v1";
    public static final String BURST_CHANGED = "burst.changed.v1";
    public static final String DEMO_RESET = "demo.reset.v1";

    private EventTypes() {
    }

    public static String full(String suffix) {
        return PREFIX + suffix;
    }

    public static String suffix(String fullType) {
        return fullType != null && fullType.startsWith(PREFIX) ? fullType.substring(PREFIX.length()) : fullType;
    }

    /** Subject on MAOP_EVENTS for an event type suffix: maop.events.v1.&lt;suffix without version&gt;. */
    public static String eventSubject(String suffix) {
        return Subjects.EVENTS_PREFIX + stripVersion(suffix);
    }

    static String stripVersion(String suffix) {
        int i = suffix.lastIndexOf(".v");
        return i > 0 ? suffix.substring(0, i) : suffix;
    }

    public static int majorVersion(String suffix) {
        int i = suffix.lastIndexOf(".v");
        try {
            return i > 0 ? Integer.parseInt(suffix.substring(i + 2)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
