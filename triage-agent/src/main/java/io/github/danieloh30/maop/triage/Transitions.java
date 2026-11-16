package io.github.danieloh30.maop.triage;

import static io.github.danieloh30.maop.common.contract.RunState.*;

import io.github.danieloh30.maop.common.contract.RunState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The permitted run state transitions (architecture.md section 4.1), as data. Anything else is rejected. */
public final class Transitions {

    private static final Map<RunState, Set<RunState>> ALLOWED = new EnumMap<>(RunState.class);

    static {
        ALLOWED.put(SUBMITTED, EnumSet.of(TRIAGING, GATHERING_EVIDENCE));
        ALLOWED.put(TRIAGING, EnumSet.of(GATHERING_EVIDENCE));
        ALLOWED.put(GATHERING_EVIDENCE, EnumSet.of(RECOMMENDING, COMPLETED));
        ALLOWED.put(RECOMMENDING, EnumSet.of(AWAITING_APPROVAL, COMPLETED));
        ALLOWED.put(AWAITING_APPROVAL, EnumSet.of(EXECUTING_ACTION, COMPLETED));
        ALLOWED.put(EXECUTING_ACTION, EnumSet.of(COMPLETED));
        for (RunState s : RunState.values()) {
            if (!s.terminal()) {
                Set<RunState> set = ALLOWED.computeIfAbsent(s, k -> EnumSet.noneOf(RunState.class));
                set.add(NEEDS_REVIEW);
                set.add(FAILED);
                set.add(CANCELLED);
                if (s != AWAITING_APPROVAL) {
                    set.add(TIMED_OUT);
                }
            } else {
                ALLOWED.put(s, EnumSet.noneOf(RunState.class));
            }
        }
    }

    private Transitions() {
    }

    public static boolean allowed(RunState from, RunState to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void check(RunState from, RunState to) {
        if (!allowed(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    public static Set<RunState> from(RunState s) {
        return Set.copyOf(ALLOWED.get(s));
    }

    public static final class IllegalTransitionException extends RuntimeException {
        public IllegalTransitionException(RunState from, RunState to) {
            super("Transition " + from + " -> " + to + " is not permitted");
        }
    }
}
