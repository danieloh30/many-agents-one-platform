package io.github.danieloh30.maop.common.messaging;

import io.github.danieloh30.maop.common.events.Subjects;
import java.time.Duration;
import java.util.List;

/**
 * The project's streams and consumers. Every service provisions this topology idempotently at startup, so a fresh
 * broker is usable regardless of which service starts first. Values must be identical in every service.
 */
public final class Topology {

    public static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(2);
    public static final long MAX_DELIVER = 3;

    private Topology() {
    }

    public static List<ConsumerSpec> consumers(Duration evidenceAckWait) {
        return List.of(
                new ConsumerSpec(Subjects.WORK_STREAM, Subjects.CONSUMER_TRIAGE, List.of(Subjects.WORK_TRIAGE),
                        Duration.ofSeconds(30), MAX_DELIVER, 16),
                new ConsumerSpec(Subjects.WORK_STREAM, Subjects.CONSUMER_EVIDENCE, List.of(Subjects.WORK_EVIDENCE),
                        evidenceAckWait, MAX_DELIVER, 64),
                new ConsumerSpec(Subjects.WORK_STREAM, Subjects.CONSUMER_RECOMMENDATION,
                        List.of(Subjects.WORK_RECOMMENDATION), Duration.ofSeconds(30), MAX_DELIVER, 8),
                new ConsumerSpec(Subjects.EVENTS_STREAM, Subjects.CONSUMER_TRIAGE_RESULTS, List.of(
                        Subjects.EVENTS_PREFIX + "task.evidence.completed",
                        Subjects.EVENTS_PREFIX + "task.recommendation.completed",
                        Subjects.EVENTS_PREFIX + "task.action.completed",
                        Subjects.EVENTS_PREFIX + "task.failed",
                        Subjects.EVENTS_PREFIX + "task.dead_lettered",
                        Subjects.EVENTS_PREFIX + "task.started"), Duration.ofSeconds(30), 5, 64));
    }

    public static ConsumerSpec consumer(String durable, Duration evidenceAckWait) {
        return consumers(evidenceAckWait).stream().filter(c -> c.durable().equals(durable)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown consumer " + durable));
    }
}
