package io.github.danieloh30.maop.common.messaging;

import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.RunContext;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.contract.FailureReason;
import io.github.danieloh30.maop.common.contract.TaskDeadLettered;
import io.github.danieloh30.maop.common.contract.TaskFailed;
import io.github.danieloh30.maop.common.contract.TaskKind;
import io.github.danieloh30.maop.common.contract.Usage;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.model.ModelGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Shared behaviour of stateless agent workers: observation events, results with deterministic event ids (so a
 * redelivered task republishes the same id and is de-duplicated), classified failures, dead letters, heartbeats.
 */
@ApplicationScoped
public class WorkerSupport {

    @Inject
    JetStreamClient js;
    @Inject
    ModelGateway model;
    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "maop")
    String service;
    @ConfigProperty(name = "maop.pod-name", defaultValue = "${HOSTNAME:local}")
    String pod;

    private ScheduledExecutorService heartbeat;

    public String service() {
        return service;
    }

    public String pod() {
        return pod;
    }

    public void started(CloudEvent command, TaskKind kind, long delivery) {
        RunContext.set(command.runId(), command.taskId());
        if (delivery > 1) {
            Counter.builder("maop.task.redeliveries").tag("kind", kind.name()).register(registry).increment();
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("taskId", command.taskId().toString());
        d.put("kind", kind.name());
        d.put("service", service);
        d.put("pod", pod);
        d.put("delivery", delivery);
        js.publishQuietly(EventTypes.eventSubject(EventTypes.TASK_STARTED), event(EventTypes.TASK_STARTED, command.runId(),
                command.taskId(), d, Ids.v5(command.taskId(), EventTypes.TASK_STARTED + "#" + delivery)));
    }

    public CloudEvent event(String type, UUID runId, UUID taskId, Object data, UUID id) {
        return CloudEvent.builder(service, type).id(id).task(runId, taskId)
                .extension(CloudEvent.EXT_MODEL_MODE, model.mode().name()).data(data).build();
    }

    /** Publish a terminal task fact with id = UUIDv5(taskId, type). Throws so the message is retried if NATS is down. */
    public void result(String type, UUID runId, UUID taskId, Object data) throws Exception {
        js.publish(EventTypes.eventSubject(type), event(type, runId, taskId, data, Ids.v5(taskId, type)));
    }

    public void failed(UUID runId, UUID taskId, TaskKind kind, TaskFailure f, long delivery, Usage usage) throws Exception {
        Counter.builder("maop.tasks").tag("kind", kind.name()).tag("result", "failed").register(registry).increment();
        result(EventTypes.TASK_FAILED, runId, taskId,
                new TaskFailed(taskId, kind, f.reason(), false, f.getMessage(), delivery, usage));
    }

    public void deadLetter(InboundMessage m, TaskKind kind, Exception failure) throws Exception {
        String reason = failure instanceof TaskFailure tf ? tf.reason() + ": " + tf.getMessage()
                : FailureReason.INTERNAL + ": " + failure.getClass().getSimpleName();
        String dlq = DeadLetters.publish(js, m.event(), reason, m.delivery());
        UUID taskId = m.event().taskId();
        js.publish(EventTypes.eventSubject(EventTypes.TASK_DEAD_LETTERED),
                event(EventTypes.TASK_DEAD_LETTERED, m.event().runId(), taskId,
                        new TaskDeadLettered(taskId, kind, reason, m.delivery(), dlq), DeadLetters.deadLetterEventId(taskId)));
    }

    public synchronized void startHeartbeat(IntSupplier activeTasks) {
        if (heartbeat != null) {
            return;
        }
        io.micrometer.core.instrument.Gauge.builder("maop.worker.active.tasks", activeTasks::getAsInt)
                .tag("service", service).register(registry);
        heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("maop-heartbeat").factory());
        heartbeat.scheduleAtFixedRate(() -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("service", service);
            d.put("pod", pod);
            d.put("activeTasks", activeTasks.getAsInt());
            d.put("modelPermitsInUse", model.permitsInUse());
            js.publishQuietly(EventTypes.eventSubject(EventTypes.WORKER_HEARTBEAT),
                    CloudEvent.builder(service, EventTypes.WORKER_HEARTBEAT).subject("workers/" + pod).data(d).build());
        }, 1, 5, TimeUnit.SECONDS);
    }

    public synchronized void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
    }

    public void modelCallEvents() {
        model.addListener(call -> {
            RunContext.Current ctx = RunContext.get();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("service", service);
            d.put("taskId", ctx == null || ctx.taskId() == null ? null : ctx.taskId().toString());
            d.put("purpose", call.purpose());
            d.put("model", call.model());
            d.put("mode", call.mode());
            d.put("durationMs", call.durationMs());
            d.put("inputTokens", call.inputTokens());
            d.put("outputTokens", call.outputTokens());
            d.put("status", "OK");
            js.publishQuietly(EventTypes.eventSubject(EventTypes.MODEL_CALL_COMPLETED), CloudEvent
                    .builder(service, EventTypes.MODEL_CALL_COMPLETED)
                    .task(ctx == null ? null : ctx.runId(), ctx == null ? null : ctx.taskId())
                    .extension(CloudEvent.EXT_MODEL_MODE, call.mode()).data(d).build());
        });
    }
}
