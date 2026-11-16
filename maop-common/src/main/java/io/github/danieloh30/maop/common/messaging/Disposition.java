package io.github.danieloh30.maop.common.messaging;

import java.time.Duration;

/** What the worker loop does with a message after the handler returns. */
public sealed interface Disposition {

    /** Acknowledge: processing (and any durable effect) is committed. */
    record Ack() implements Disposition {
    }

    /** Negative acknowledgement with redelivery after the given delay. */
    record Nak(Duration delay, String reason) implements Disposition {
    }

    /**
     * Leave the message unacknowledged so JetStream redelivers it after AckWait. Used only for explicit fault
     * injection (DROP_ACK_ONCE) to demonstrate redelivery.
     */
    record Abandon(String reason) implements Disposition {
    }

    Disposition ACK = new Ack();

    static Disposition nak(Duration delay, String reason) {
        return new Nak(delay, reason);
    }
}
