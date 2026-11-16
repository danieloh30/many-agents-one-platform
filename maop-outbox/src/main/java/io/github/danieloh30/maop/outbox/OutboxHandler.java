package io.github.danieloh30.maop.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Publishes one outbox row of a given kind. Must be idempotent: a row can be delivered more than once. */
public interface OutboxHandler {

    String kind();

    void deliver(UUID id, String subject, JsonNode payload) throws Exception;
}
