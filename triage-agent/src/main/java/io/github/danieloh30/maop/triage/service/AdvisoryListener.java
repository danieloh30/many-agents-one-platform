package io.github.danieloh30.maop.triage.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.model.TaskDeadLettered;
import io.github.danieloh30.maop.common.model.TaskKind;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.messaging.DeadLetters;
import io.github.danieloh30.maop.common.messaging.JetStreamClient;
import io.nats.client.Dispatcher;
import io.nats.client.api.MessageInfo;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Backstop for messages that exhausted MaxDeliver without any handler finishing (e.g. the worker pod was killed on
 * the final delivery). Such messages stay in a work-queue stream; this copies them to the DLQ, removes them from
 * the work stream, and folds a task.dead_lettered fact into the run.
 */
@ApplicationScoped
public class AdvisoryListener {

    private static final Logger LOG = Logger.getLogger(AdvisoryListener.class);

    @Inject
    JetStreamClient js;
    @Inject
    ResultFolder folder;

    private Dispatcher dispatcher;

    public void start() {
        dispatcher = js.dispatcher();
        dispatcher.subscribe(Subjects.MAX_DELIVERIES_ADVISORY,
                msg -> Thread.ofVirtual().start(() -> handle(Json.parse(new String(msg.getData())))));
    }

    void handle(JsonNode advisory) {
        String stream = advisory.path("stream").asText();
        long seq = advisory.path("stream_seq").asLong();
        long deliveries = advisory.path("deliveries").asLong();
        try {
            MessageInfo mi;
            try {
                mi = js.management().getMessage(stream, seq);
            } catch (Exception gone) {
                return; // already acknowledged/removed (the worker dead-lettered it itself)
            }
            CloudEvent command = CloudEventCodec.fromBinary(mi.getHeaders(), mi.getData());
            String dlq = DeadLetters.publish(js, command, "max deliveries reached without completion", deliveries);
            js.management().deleteMessage(stream, seq);
            UUID taskId = command.taskId();
            if (taskId == null) {
                return;
            }
            TaskKind kind = switch (command.typeSuffix()) {
                case EventTypes.EVIDENCE_REQUESTED -> TaskKind.EVIDENCE;
                case EventTypes.ACTION_REQUESTED -> TaskKind.ACTION;
                default -> TaskKind.RECOMMENDATION;
            };
            CloudEvent fact = CloudEvent.builder(Workflow.SERVICE, EventTypes.TASK_DEAD_LETTERED)
                    .id(DeadLetters.deadLetterEventId(taskId)).task(command.runId(), taskId)
                    .data(new TaskDeadLettered(taskId, kind, "max deliveries reached without completion", deliveries, dlq))
                    .build();
            QuarkusTransaction.requiringNew().run(() -> folder.fold(fact));
            LOG.infof("Dead-lettered %s (stream seq %d) to %s", command.type(), seq, dlq);
        } catch (Exception e) {
            LOG.warnf("Advisory handling failed for %s seq %d: %s", stream, seq, e.getMessage());
        }
    }
}
