package io.github.danieloh30.maop.triage.service;

import io.github.danieloh30.maop.triage.model.Run;
import io.github.danieloh30.maop.triage.model.Task;
import io.github.danieloh30.maop.triage.model.Transitions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.model.ActionCommand;
import io.github.danieloh30.maop.common.model.Classification;
import io.github.danieloh30.maop.common.model.EvidenceCompleteness;
import io.github.danieloh30.maop.common.model.EvidenceItem;
import io.github.danieloh30.maop.common.model.EvidenceTaskCommand;
import io.github.danieloh30.maop.common.model.FaultInjection;
import io.github.danieloh30.maop.common.model.Outcome;
import io.github.danieloh30.maop.common.model.PlanSource;
import io.github.danieloh30.maop.common.model.ProposedAction;
import io.github.danieloh30.maop.common.model.RecommendationCommand;
import io.github.danieloh30.maop.common.model.RunState;
import io.github.danieloh30.maop.common.model.TaskBudget;
import io.github.danieloh30.maop.common.model.TaskKind;
import io.github.danieloh30.maop.common.model.TaskState;
import io.github.danieloh30.maop.common.model.TriagePlan;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.agentic.ModelGateway;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.github.danieloh30.maop.outbox.Outbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * State transitions and delegation for runs. Every method runs inside the caller's transaction and writes outbox
 * rows in it, so state and messages never diverge.
 */
@ApplicationScoped
public class Workflow {

    public static final String SERVICE = "triage-agent";

    @Inject
    RunStore store;
    @Inject
    Outbox outbox;
    @Inject
    MeterRegistry registry;
    @Inject
    ModelGateway model;

    @ConfigProperty(name = "maop.run.execution-window", defaultValue = "60s")
    Duration executionWindow;

    public static final TaskBudget EVIDENCE_BUDGET = new TaskBudget(2, 4, 800);
    public static final TaskBudget RECOMMENDATION_BUDGET = new TaskBudget(2, 2, 800);
    public static final TaskBudget ACTION_BUDGET = new TaskBudget(0, 1, 0);

    @Transactional(Transactional.TxType.MANDATORY)
    public void transition(Run r, RunState to, Outcome outcome, String reason) {
        RunState from = r.state;
        Transitions.check(from, to);
        r.state = to;
        if (to.terminal()) {
            r.outcome = outcome;
            r.outcomeDetail = reason;
        }
        store.update(r);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", r.runId.toString());
        data.put("from", from.name());
        data.put("to", to.name());
        data.put("outcome", outcome == null ? null : outcome.name());
        data.put("version", r.version);
        data.put("reason", reason);
        store.timeline(r.runId, "STATE_CHANGED", from, to, null, reason == null ? from + " → " + to : reason, data);
        outbox.event(EventTypes.eventSubject(EventTypes.RUN_STATE_CHANGED), event(EventTypes.RUN_STATE_CHANGED, r, null, data));
        Span.current().addEvent("run.state_changed", io.opentelemetry.api.common.Attributes.builder()
                .put("maop.run.from", from.name()).put("maop.run.to", to.name()).build());
        if (to.terminal()) {
            Counter.builder("maop.runs.terminal").tag("state", to.name())
                    .tag("outcome", outcome == null ? "NONE" : outcome.name()).register(registry).increment();
            Timer.builder("maop.run.duration").tag("state", to.name()).publishPercentileHistogram().register(registry)
                    .record(Duration.between(r.createdAt, Instant.now()));
            for (Task t : store.tasks(r.runId)) {
                if (t.state == TaskState.DISPATCHED) {
                    t.state = to == RunState.CANCELLED ? TaskState.CANCELLED : TaskState.EXPIRED;
                    t.finishedAt = Instant.now();
                    store.updateTask(t);
                }
            }
        }
    }

    CloudEvent event(String type, Run r, UUID taskId, Object data) {
        return CloudEvent.builder(SERVICE, type).task(r.runId, taskId)
                .extension(CloudEvent.EXT_DEADLINE, r.deadline == null ? null : r.deadline.toString())
                .extension(CloudEvent.EXT_MODEL_MODE, r.modelMode).data(data).build();
    }

    /** Apply a validated plan: GATHERING_EVIDENCE plus one evidence command per task. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void startEvidence(Run r, TriagePlan plan, String truncationNote) {
        if (plan.category() != null) {
            r.classification = Json.tree(plan.classification());
        }
        transition(r, RunState.GATHERING_EVIDENCE, null,
                "Dispatched " + plan.tasks().size() + " evidence task(s)" + (truncationNote == null ? "" : "; " + truncationNote));
        int index = 0;
        for (TriagePlan.PlannedTask p : plan.tasks()) {
            Task t = new Task();
            t.taskId = Ids.v7();
            t.runId = r.runId;
            t.kind = TaskKind.EVIDENCE;
            t.topic = p.topic();
            t.state = TaskState.DISPATCHED;
            store.insertTask(t);
            // Fault injection applies to exactly one task so the redelivery is easy to follow.
            FaultInjection fi = index++ == 0 ? r.faultInjection : FaultInjection.NONE;
            EvidenceTaskCommand cmd = new EvidenceTaskCommand(r.runId, t.taskId, p.topic(), r.incident, p.question(),
                    EVIDENCE_BUDGET, r.deadline, fi, r.scenario.name());
            outbox.event(Subjects.WORK_EVIDENCE, event(EventTypes.EVIDENCE_REQUESTED, r, t.taskId, cmd));
            dispatched(r, t, "Evidence task " + p.topic() + " queued");
        }
    }

    private void dispatched(Run r, Task t, String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("taskId", t.taskId.toString());
        d.put("kind", t.kind.name());
        d.put("topic", t.topic == null ? null : t.topic.name());
        store.timeline(r.runId, "TASK_DISPATCHED", null, null, t.taskId, message, d);
        outbox.event(EventTypes.eventSubject(EventTypes.TASK_DISPATCHED), event(EventTypes.TASK_DISPATCHED, r, t.taskId, d));
    }

    /** Called after any task reached a terminal state: decide the next step when all evidence is in. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void afterTaskTerminal(Run r) {
        if (r.usage.totalTokens() > r.budget.maxTotalTokens()) {
            transition(r, RunState.NEEDS_REVIEW, Outcome.BUDGET_EXHAUSTED,
                    "Run used " + r.usage.totalTokens() + " tokens (limit " + r.budget.maxTotalTokens() + ")");
            return;
        }
        if (r.state != RunState.GATHERING_EVIDENCE) {
            return;
        }
        List<Task> tasks = store.tasks(r.runId).stream().filter(t -> t.kind == TaskKind.EVIDENCE).toList();
        if (tasks.stream().anyMatch(t -> t.state == TaskState.DISPATCHED)) {
            return;
        }
        List<EvidenceItem> evidence = new ArrayList<>();
        for (Task t : tasks) {
            if (t.state == TaskState.COMPLETED && t.evidence != null) {
                evidence.add(Json.convert(t.evidence, EvidenceItem.class));
            }
        }
        if (evidence.isEmpty()) {
            transition(r, RunState.NEEDS_REVIEW, Outcome.QUORUM_NOT_MET, "No evidence task completed");
            return;
        }
        EvidenceCompleteness completeness = evidence.size() == tasks.size() ? EvidenceCompleteness.COMPLETE
                : EvidenceCompleteness.PARTIAL;
        r.evidenceCompleteness = completeness.name();
        if (r.planSource == PlanSource.PRESET) {
            transition(r, RunState.COMPLETED, Outcome.EVIDENCE_ONLY,
                    evidence.size() + "/" + tasks.size() + " evidence task(s) completed (load run, no recommendation)");
            return;
        }
        if (r.usage.modelCalls() + RECOMMENDATION_BUDGET.maxModelCalls() > r.budget.maxModelCalls()) {
            transition(r, RunState.NEEDS_REVIEW, Outcome.BUDGET_EXHAUSTED, "No model-call budget left for a recommendation");
            return;
        }
        transition(r, RunState.RECOMMENDING, null, "Evidence " + completeness.name().toLowerCase() + ": "
                + evidence.size() + "/" + tasks.size() + " task(s)");
        Task t = new Task();
        t.taskId = Ids.v7();
        t.runId = r.runId;
        t.kind = TaskKind.RECOMMENDATION;
        t.state = TaskState.DISPATCHED;
        store.insertTask(t);
        Classification cls = r.classification == null ? null : Json.convert(r.classification, Classification.class);
        RecommendationCommand cmd = new RecommendationCommand(r.runId, t.taskId, r.incident, cls, evidence, completeness,
                r.remediationMode, RECOMMENDATION_BUDGET, r.deadline);
        outbox.event(Subjects.WORK_RECOMMENDATION, event(EventTypes.RECOMMENDATION_REQUESTED, r, t.taskId, cmd));
        dispatched(r, t, "Recommendation requested");
    }

    /** Approved: register the grant (outbox, first) then dispatch the simulated action (outbox, second). */
    @Transactional(Transactional.TxType.MANDATORY)
    public UUID startAction(Run r, ProposedAction action, Duration approvalValidity) {
        UUID approvalId = Ids.v7();
        Instant now = Instant.now();
        ObjectNode approval = Json.MAPPER.createObjectNode();
        approval.put("approvalId", approvalId.toString());
        approval.put("decision", "APPROVE");
        approval.put("actionDigest", action.actionDigest());
        approval.put("approver", "demo-operator");
        approval.put("decidedAt", now.toString());
        approval.put("expiresAt", now.plus(approvalValidity).toString());
        r.approval = approval;
        r.deadline = now.plus(executionWindow);
        transition(r, RunState.EXECUTING_ACTION, null, "Approved by demo-operator (simulation only)");
        ObjectNode grant = Json.MAPPER.createObjectNode();
        grant.put("approvalId", approvalId.toString());
        grant.put("runId", r.runId.toString());
        grant.put("actionDigest", action.actionDigest());
        grant.put("approver", "demo-operator");
        grant.put("approvedAt", now.toString());
        grant.put("expiresAt", now.plus(approvalValidity).toString());
        grant.put("traceparent", Tracing.currentTraceparent());
        outbox.row(Ids.v7(), ApprovalGrantHandler.KIND, null, grant);
        Task t = new Task();
        t.taskId = Ids.v7();
        t.runId = r.runId;
        t.kind = TaskKind.ACTION;
        t.state = TaskState.DISPATCHED;
        store.insertTask(t);
        ActionCommand cmd = new ActionCommand(r.runId, t.taskId, approvalId, action, ACTION_BUDGET, r.deadline);
        outbox.event(Subjects.WORK_RECOMMENDATION, event(EventTypes.ACTION_REQUESTED, r, t.taskId, cmd));
        dispatched(r, t, "Simulated action requested");
        store.timeline(r.runId, "APPROVAL", null, null, null, "Approval " + approvalId + " granted for " + action.actionType(),
                approval);
        return approvalId;
    }

    /** Run follow-up work (approval, sweeper) inside the run's own trace, linked to the triggering span. */
    public Scope runTrace(Run r, String spanName) {
        Span trigger = Span.current();
        Context parent = Tracing.fromTraceparent(r.traceparent);
        var builder = Tracing.tracer().spanBuilder(spanName).setParent(parent).setAttribute("maop.run.id", r.runId.toString());
        if (trigger.getSpanContext().isValid()) {
            builder.addLink(trigger.getSpanContext());
        }
        Span span = builder.startSpan();
        Scope scope = span.makeCurrent();
        return () -> {
            scope.close();
            span.end();
        };
    }

    public static JsonNode usageNode(io.github.danieloh30.maop.common.model.Usage u) {
        return Json.tree(u);
    }

    public ModelGateway model() {
        return model;
    }
}
