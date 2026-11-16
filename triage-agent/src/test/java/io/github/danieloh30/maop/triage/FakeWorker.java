package io.github.danieloh30.maop.triage;

import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.messaging.JetStreamClient;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Plays the role of a worker in tests: pulls commands for one run from a real durable consumer. */
final class FakeWorker {

    private FakeWorker() {
    }

    static List<CloudEvent> pull(JetStreamClient js, String stream, String durable, UUID runId, int expected, Duration timeout)
            throws Exception {
        JetStreamSubscription sub = js.jetStream().subscribe(null, PullSubscribeOptions.bind(stream, durable));
        List<CloudEvent> out = new ArrayList<>();
        Instant until = Instant.now().plus(timeout);
        while (out.size() < expected && Instant.now().isBefore(until)) {
            for (Message m : sub.fetch(expected, Duration.ofMillis(500))) {
                CloudEvent e = CloudEventCodec.fromBinary(m.getHeaders(), m.getData());
                m.ack();
                if (runId.equals(e.runId())) {
                    out.add(e);
                }
            }
        }
        sub.unsubscribe();
        return out;
    }
}
