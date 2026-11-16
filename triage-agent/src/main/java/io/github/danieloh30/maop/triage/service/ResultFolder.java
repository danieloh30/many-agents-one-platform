package io.github.danieloh30.maop.triage.service;

import io.github.danieloh30.maop.triage.model.Run;
import io.github.danieloh30.maop.triage.model.Task;

import io.github.danieloh30.maop.common.model.ActionCompleted;
import io.github.danieloh30.maop.common.model.EvidenceCompleted;
import io.github.danieloh30.maop.common.model.Outcome;
import io.github.danieloh30.maop.common.model.Recommendation;
import io.github.danieloh30.maop.common.model.RecommendationCompleted;
import io.github.danieloh30.maop.common.model.RunState;
import io.github.danieloh30.maop.common.model.TaskDeadLettered;
import io.github.danieloh30.maop.common.model.TaskFailed;
import io.github.danieloh30.maop.common.model.TaskKind;
import io.github.danieloh30.maop.common.model.TaskState;
import io.github.danieloh30.maop.common.model.Usage;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.messaging.Disposition;
import io.github.danieloh30.maop.common.messaging.InboundMessage;
import io.github.danieloh30.maop.common.messaging.MessageHandler;
import io.github.danieloh30.maop.outbox.Inbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Folds task results into the run. One transaction per event: inbox de-duplication, a row lock on the run
 * (serialises concurrent results of the same run), state guards, outbox writes — then the worker acknowledges.
 */
@ApplicationScoped
public class ResultFolder implements MessageHandler {

    @Inject
    RunStore store;
    @Inject
    Workflow workflow;
    @Inject
    Inbox inbox;
    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "maop.run.approval-window", defaultValue = "10m")
    Duration approvalWindow;

    @Override
    @Transactional
    public Disposition handle(InboundMessage m) {
        return fold(m.event());
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Disposition fold(CloudEvent e) {
        if (!inbox.firstDelivery(Subjects.CONSUMER_TRIAGE_RESULTS, e.id())) {
            return Disposition.ACK; // duplicate delivery or duplicate publish
        }
        if (e.runId() == null) {
            return Disposition.ACK;
        }
        Optional<Run> o = store.lock(e.runId());
        if (o.isEmpty()) {
            return Disposition.ACK; // unknown run (e.g. after a demo reset)
        }
        Run r = o.get();
        Optional<Task> task = e.taskId() == null ? Optional.empty()
                : store.tasks(r.runId).stream().filter(t -> t.taskId.equals(e.taskId())).findFirst();
        String type = e.typeSuffix();
        if (EventTypes.TASK_STARTED.equals(type)) {
            task.ifPresent(t -> {
                int delivery = e.data().path("delivery").asInt(1);
                if (delivery > t.deliveries) {
                    t.deliveries = delivery;
                    store.updateTask(t);
                }
                store.timeline(r.runId, "TASK_STARTED", null, null, t.taskId, t.kind + " task started on "
                        + e.data().path("pod").asText("unknown pod") + " (delivery " + delivery + ")",
                        Map.of("delivery", delivery, "pod", e.data().path("pod").asText(""), "service",
                                e.data().path("service").asText("")));
            });
            return Disposition.ACK;
        }
        if (r.state.terminal()) {
            store.timeline(r.runId, "IGNORED_LATE", null, null, e.taskId(), "Late " + type + " ignored (run is " + r.state + ")",
                    null);
            return Disposition.ACK;
        }
        if (task.isEmpty() || task.get().state.terminal()) {
            store.timeline(r.runId, "IGNORED_LATE", null, null, e.taskId(), "Result for unknown or finished task ignored", null);
            return Disposition.ACK;
        }
        Task t = task.get();
        switch (type) {
            case EventTypes.EVIDENCE_COMPLETED -> {
                EvidenceCompleted ec = e.dataAs(EvidenceCompleted.class);
                finish(t, TaskState.COMPLETED, ec.usage(), ec.deliveries(), null, null);
                t.evidence = Json.tree(ec.evidence());
                store.updateTask(t);
                r.usage = r.usage.plus(ec.usage());
                store.timeline(r.runId, "TASK_COMPLETED", null, null, t.taskId, "Evidence " + t.topic + ": " + ec.evidence().finding(),
                        Map.of("deliveries", ec.deliveries()));
                count(t, "completed");
                workflow.afterTaskTerminal(r);
                persistIfUnchanged(r);
            }
            case EventTypes.RECOMMENDATION_COMPLETED -> {
                RecommendationCompleted rc = e.dataAs(RecommendationCompleted.class);
                finish(t, TaskState.COMPLETED, rc.usage(), rc.deliveries(), null, null);
                store.updateTask(t);
                r.usage = r.usage.plus(rc.usage());
                count(t, "completed");
                if (r.state != RunState.RECOMMENDING) {
                    store.timeline(r.runId, "IGNORED_LATE", null, null, t.taskId, "Recommendation arrived in state " + r.state, null);
                    persistIfUnchanged(r);
                    return Disposition.ACK;
                }
                Recommendation rec = rc.recommendation();
                r.recommendation = Json.tree(rec);
                if (rc.executionAttempt() != null) {
                    r.executionAttempt = Json.tree(rc.executionAttempt());
                    if ("DENIED".equals(rc.executionAttempt().status())) {
                        store.timeline(r.runId, "POLICY_DENIAL", null, null, t.taskId,
                                "Unapproved " + rec.proposedAction().actionType() + " denied by policy before execution: "
                                        + String.join(", ", rc.executionAttempt().reasons()),
                                Json.tree(rc.executionAttempt()));
                    }
                }
                store.timeline(r.runId, "TASK_COMPLETED", null, null, t.taskId, "Recommendation: " + rec.summary(),
                        Map.of("deliveries", rc.deliveries()));
                if (r.usage.totalTokens() > r.budget.maxTotalTokens()) {
                    workflow.afterTaskTerminal(r);
                } else if (rec.proposedAction().sideEffect()) {
                    r.approvalDeadline = Instant.now().plus(approvalWindow);
                    workflow.transition(r, RunState.AWAITING_APPROVAL, null, "Proposed " + rec.proposedAction().actionType()
                            + " on " + rec.proposedAction().target() + " awaits human approval");
                } else {
                    workflow.transition(r, RunState.COMPLETED, Outcome.NO_ACTION_REQUIRED,
                            "Recommended " + rec.proposedAction().actionType() + " (no side effect)");
                }
            }
            case EventTypes.ACTION_COMPLETED -> {
                ActionCompleted ac = e.dataAs(ActionCompleted.class);
                finish(t, TaskState.COMPLETED, ac.usage(), ac.deliveries(), null, null);
                store.updateTask(t);
                r.usage = r.usage.plus(ac.usage());
                count(t, "completed");
                if (r.state == RunState.EXECUTING_ACTION) {
                    workflow.transition(r, RunState.COMPLETED, Outcome.REMEDIATION_SIMULATED,
                            "Simulated action recorded (invocation " + ac.invocationId() + (ac.replayed() ? ", replayed" : "") + ")");
                }
            }
            case EventTypes.TASK_FAILED -> {
                TaskFailed tf = e.dataAs(TaskFailed.class);
                finish(t, TaskState.FAILED, tf.usage(), tf.deliveries(), tf.reason().name(), tf.detail());
                store.updateTask(t);
                r.usage = r.usage.plus(tf.usage());
                store.timeline(r.runId, "TASK_FAILED", null, null, t.taskId, t.kind + " task failed: " + tf.reason(),
                        Map.of("reason", tf.reason().name(), "detail", tf.detail() == null ? "" : tf.detail()));
                count(t, "failed");
                afterFailure(r, t, Outcome.from(tf.reason()), tf.detail());
            }
            case EventTypes.TASK_DEAD_LETTERED -> {
                TaskDeadLettered dl = e.dataAs(TaskDeadLettered.class);
                finish(t, TaskState.DEAD_LETTERED, null, dl.deliveries(), "DEAD_LETTERED", dl.reason());
                store.updateTask(t);
                store.timeline(r.runId, "TASK_DEAD_LETTERED", null, null, t.taskId, t.kind + " task dead-lettered to "
                        + dl.dlqSubject() + " after " + dl.deliveries() + " deliveries", Json.tree(dl));
                count(t, "dead_lettered");
                afterFailure(r, t, Outcome.DEAD_LETTERED, dl.reason());
            }
            default -> {
                return Disposition.ACK;
            }
        }
        return Disposition.ACK;
    }

    private void afterFailure(Run r, Task t, Outcome outcome, String detail) {
        if (t.kind == TaskKind.EVIDENCE) {
            workflow.afterTaskTerminal(r);
            persistIfUnchanged(r);
        } else {
            workflow.transition(r, RunState.NEEDS_REVIEW, outcome, t.kind + " task: " + (detail == null ? outcome : detail));
        }
    }

    /** Persist usage/completeness changes (the row is locked, so this cannot conflict). */
    private void persistIfUnchanged(Run r) {
        store.update(r);
    }

    private void finish(Task t, TaskState s, Usage usage, long deliveries, String reason, String detail) {
        t.state = s;
        t.finishedAt = Instant.now();
        t.usage = usage == null ? null : Json.tree(usage);
        t.deliveries = (int) Math.max(t.deliveries, deliveries);
        t.failureReason = reason;
        t.detail = detail;
    }

    private void count(Task t, String result) {
        Counter.builder("maop.tasks").tag("kind", t.kind.name()).tag("result", result).register(registry).increment();
    }
}
