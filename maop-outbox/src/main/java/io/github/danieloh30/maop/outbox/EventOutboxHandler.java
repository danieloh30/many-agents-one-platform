package io.github.danieloh30.maop.outbox;

import tools.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.messaging.JetStreamClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/** Publishes EVENT rows to JetStream. Re-publishing the same row is de-duplicated by Nats-Msg-Id and the inbox. */
@ApplicationScoped
public class EventOutboxHandler implements OutboxHandler {

    @Inject
    JetStreamClient jetStream;

    @Override
    public String kind() {
        return Outbox.KIND_EVENT;
    }

    @Override
    public void deliver(UUID id, String subject, JsonNode payload) throws Exception {
        jetStream.publish(subject, CloudEventCodec.fromStructured(payload));
    }
}
