package io.github.danieloh30.maop.common.messaging;

import io.github.danieloh30.maop.common.events.CloudEvent;
import io.nats.client.Message;

/** A delivered JetStream message decoded as a CloudEvent, with its delivery attempt (1-based). */
public record InboundMessage(Message raw, CloudEvent event, long delivery, long maxDeliver, String consumer) {

    public boolean finalDelivery() {
        return maxDeliver > 0 && delivery >= maxDeliver;
    }
}
