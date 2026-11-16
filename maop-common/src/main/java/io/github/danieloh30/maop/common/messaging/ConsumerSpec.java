package io.github.danieloh30.maop.common.messaging;

import java.time.Duration;
import java.util.List;

/** A durable pull consumer definition (contracts.md section 3.2). */
public record ConsumerSpec(String stream, String durable, List<String> filterSubjects, Duration ackWait,
        long maxDeliver, long maxAckPending) {
}
