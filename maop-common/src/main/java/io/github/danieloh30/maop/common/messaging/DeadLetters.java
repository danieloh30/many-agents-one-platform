package io.github.danieloh30.maop.common.messaging;

import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.json.Json;
import io.nats.client.PublishOptions;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import java.util.UUID;

/** Copies an undeliverable command to MAOP_DLQ (structured CloudEvent plus failure metadata headers). */
public final class DeadLetters {

    private DeadLetters() {
    }

    public static String kindOf(CloudEvent command) {
        return switch (command.typeSuffix()) {
            case EventTypes.EVIDENCE_REQUESTED -> "evidence";
            case EventTypes.RECOMMENDATION_REQUESTED -> "recommendation";
            case EventTypes.ACTION_REQUESTED -> "action";
            case EventTypes.RUN_TRIAGE_REQUESTED -> "triage";
            default -> "unknown";
        };
    }

    /** Deterministic id of the task.dead_lettered fact for a task (shared by worker and advisory paths). */
    public static UUID deadLetterEventId(UUID taskOrRunId) {
        return io.github.danieloh30.maop.common.Ids.v5(taskOrRunId, EventTypes.TASK_DEAD_LETTERED);
    }

    public static String publish(JetStreamClient js, CloudEvent command, String reason, long deliveries) throws Exception {
        String subject = Subjects.DLQ_PREFIX + kindOf(command);
        Headers h = new Headers();
        h.put("Content-Type", CloudEventCodec.STRUCTURED_CONTENT_TYPE);
        h.put("Maop-Dlq-Reason", reason == null ? "unknown" : reason.length() > 200 ? reason.substring(0, 200) : reason);
        h.put("Maop-Dlq-Deliveries", Long.toString(deliveries));
        h.put(CloudEventCodec.NATS_MSG_ID, "dlq-" + command.id());
        NatsMessage msg = NatsMessage.builder().subject(subject).headers(h)
                .data(Json.bytes(CloudEventCodec.toStructured(command))).build();
        js.jetStream().publish(msg, PublishOptions.builder().messageId("dlq-" + command.id()).build());
        return subject;
    }
}
