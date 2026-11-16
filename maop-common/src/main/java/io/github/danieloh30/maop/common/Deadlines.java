package io.github.danieloh30.maop.common;

import java.time.Duration;
import java.time.Instant;

/** Deadline arithmetic (architecture.md section 4.2). */
public final class Deadlines {

    /** A call is not started with less than this remaining. */
    public static final Duration MIN_REMAINING = Duration.ofSeconds(3);
    /** Safety margin subtracted from the remaining time when deriving a per-call timeout. */
    public static final Duration MARGIN = Duration.ofSeconds(2);

    private Deadlines() {
    }

    public static Duration remaining(Instant deadline) {
        return deadline == null ? Duration.ofDays(1) : Duration.between(Instant.now(), deadline);
    }

    /** min(configured, remaining - margin); throws {@link TaskFailure} if less than the minimum remains. */
    public static Duration callTimeout(Instant deadline, Duration configured, String what) {
        Duration remaining = remaining(deadline);
        if (remaining.compareTo(MIN_REMAINING) < 0) {
            throw TaskFailure.deadline(what);
        }
        Duration derived = remaining.minus(MARGIN);
        return derived.compareTo(configured) < 0 ? derived : configured;
    }

    public static boolean passed(Instant deadline) {
        return deadline != null && Instant.now().isAfter(deadline);
    }
}
