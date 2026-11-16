package io.github.danieloh30.maop.triage;

import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.contract.Budget;
import io.github.danieloh30.maop.common.contract.FaultInjection;
import io.github.danieloh30.maop.common.contract.Incident;
import io.github.danieloh30.maop.common.contract.Outcome;
import io.github.danieloh30.maop.common.contract.PlanSource;
import io.github.danieloh30.maop.common.contract.ProposedAction;
import io.github.danieloh30.maop.common.contract.Recommendation;
import io.github.danieloh30.maop.common.contract.RemediationMode;
import io.github.danieloh30.maop.common.contract.RunState;
import io.github.danieloh30.maop.common.contract.Scenario;
import io.github.danieloh30.maop.common.contract.Topic;
import io.github.danieloh30.maop.common.contract.TriageCommand;
import io.github.danieloh30.maop.common.contract.TriagePlan;
import io.github.danieloh30.maop.common.contract.Usage;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.messaging.JetStreamClient;
import io.github.danieloh30.maop.common.model.ModelGateway;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.github.danieloh30.maop.outbox.Outbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Commands from the console API: create, approve/reject, cancel, bursts, reset. */
@ApplicationScoped
public class RunService {

    @Inject
    RunStore store;
    @Inject
    Workflow workflow;
    @Inject
    Outbox outbox;
    @Inject
    ModelGateway model;
    @Inject
    JetStreamClient js;
    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "maop.run.deadline", defaultValue = "120s")
    Duration runDeadline;
    @ConfigProperty(name = "maop.run.approval-validity", defaultValue = "10m")
    Duration approvalValidity;
    @ConfigProperty(name = "maop.limits.max-active-runs", defaultValue = "25")
    int maxActiveRuns;
    @ConfigProperty(name = "maop.limits.max-runs-per-hour", defaultValue = "120")
    int maxRunsPerHour;
    @ConfigProperty(name = "maop.burst.max-runs", defaultValue = "12")
    int burstMaxRuns;
    @ConfigProperty(name = "maop.fault-injection.enabled", defaultValue = "true")
    boolean faultInjectionEnabled;

    public record Created(Run run, boolean replayed) {
    }

    public Created create(CreateRunRequest req, String idempotencyKey) {
        validate(req);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new Problems.ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header (max 200 chars) is required");
        }
        String hash = Json.digest(req);
        return QuarkusTransaction.requiringNew().call(() -> {
            UUID runId = Ids.v7();
            var existing = store.claimIdempotencyKey(idempotencyKey, hash, runId);
            if (existing.isPresent()) {
                if (!existing.get()[1].equals(hash)) {
                    throw new Problems.ApiException(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used with a different request");
                }
                return new Created(store.find(UUID.fromString(existing.get()[0])).orElseThrow(), true);
            }
            admission(1);
            Run r = newRun(runId, req, PlanSource.MODEL, null);
            store.insert(r);
            store.timeline(r.runId, "STATE_CHANGED", null, RunState.SUBMITTED, null, "Incident submitted", null);
            outbox.event(Subjects.WORK_TRIAGE, workflow.event(EventTypes.RUN_TRIAGE_REQUESTED, r, null, new TriageCommand(r.runId)));
            outbox.event(EventTypes.eventSubject(EventTypes.RUN_STATE_CHANGED), workflow.event(EventTypes.RUN_STATE_CHANGED, r, null,
                    Map.of("runId", r.runId.toString(), "from", "NONE", "to", "SUBMITTED", "version", 1)));
            Counter.builder("maop.runs.started").tag("scenario", r.scenario.name()).register(registry).increment();
            return new Created(r, false);
        });
    }

    private Run newRun(UUID runId, CreateRunRequest req, PlanSource planSource, UUID burstId) {
        Run r = new Run();
        r.runId = runId;
        r.state = RunState.SUBMITTED;
        r.scenario = req.scenario() == null ? Scenario.CUSTOM : req.scenario();
        r.remediationMode = req.remediationMode() == null ? RemediationMode.PROPOSE_ONLY : req.remediationMode();
        r.faultInjection = req.faultInjection() == null ? FaultInjection.NONE : req.faultInjection();
        r.planSource = planSource;
        r.burstId = burstId;
        r.incident = new Incident(req.title().strip(), req.description().strip(), req.service().strip());
        r.budget = Budget.defaults().clamped();
        r.usage = Usage.ZERO;
        r.modelMode = model.mode().name();
        r.modelName = model.displayName();
        r.traceId = Tracing.traceId(io.opentelemetry.context.Context.current());
        r.traceparent = Tracing.currentTraceparent();
        r.labels = Json.tree(req.labels() == null ? Map.of() : req.labels());
        r.createdAt = Instant.now();
        r.deadline = r.createdAt.plus(runDeadline);
        Span.current().setAttribute("maop.run.id", r.runId.toString());
        return r;
    }

    private void validate(CreateRunRequest req) {
        if (req == null) {
            throw new Problems.ApiException(422, "INVALID_REQUEST", "Body is required");
        }
        if (req.title() == null || req.title().isBlank() || req.title().length() > 120) {
            throw new Problems.ApiException(422, "INVALID_REQUEST", "title is required (max 120 characters)");
        }
        if (req.description() == null || req.description().isBlank() || req.description().length() > 2000) {
            throw new Problems.ApiException(422, "INVALID_REQUEST", "description is required (max 2000 characters)");
        }
        if (req.service() == null || req.service().isBlank() || req.service().length() > 120) {
            throw new Problems.ApiException(422, "INVALID_REQUEST", "service is required (max 120 characters)");
        }
        if (req.faultInjection() != null && req.faultInjection() != FaultInjection.NONE && !faultInjectionEnabled) {
            throw new Problems.ApiException(422, "FAULT_INJECTION_DISABLED", "Fault injection is disabled in this deployment");
        }
    }

    private void admission(int newRuns) {
        if (store.activeRuns() + newRuns > maxActiveRuns) {
            throw new Problems.ApiException(429, "TOO_MANY_ACTIVE_RUNS", "At most " + maxActiveRuns + " runs may be active");
        }
        if (store.createdSince(Instant.now().minus(Duration.ofHours(1))) + newRuns > maxRunsPerHour) {
            throw new Problems.ApiException(429, "RATE_LIMITED", "At most " + maxRunsPerHour + " runs per hour");
        }
    }

    public Run decide(UUID runId, ApprovalRequest req, Integer ifMatch) {
        if (req == null || req.decision() == null || req.actionDigest() == null) {
            throw new Problems.ApiException(422, "INVALID_REQUEST", "decision and actionDigest are required");
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            Run r = store.lock(runId).orElseThrow(() -> new Problems.ApiException(404, "NOT_FOUND", "No such run"));
            if (ifMatch != null && ifMatch != r.version) {
                throw new Problems.ApiException(412, "VERSION_MISMATCH", "Run changed (version " + r.version + ")");
            }
            if (r.state != RunState.AWAITING_APPROVAL) {
                throw new Problems.ApiException(409, "INVALID_STATE", "Run is " + r.state + ", not AWAITING_APPROVAL");
            }
            if (r.approvalDeadline != null && Instant.now().isAfter(r.approvalDeadline)) {
                throw new Problems.ApiException(409, "APPROVAL_EXPIRED", "The approval window has passed");
            }
            ProposedAction action = Json.convert(r.recommendation, Recommendation.class).proposedAction();
            if (!action.actionDigest().equals(req.actionDigest())) {
                throw new Problems.ApiException(409, "DIGEST_MISMATCH", "actionDigest does not match the proposed action");
            }
            try (Scope ignored = workflow.runTrace(r, "run.approval")) {
                switch (req.decision()) {
                    case "APPROVE" -> workflow.startAction(r, action, approvalValidity);
                    case "REJECT" -> {
                        r.approval = Json.MAPPER.createObjectNode().put("decision", "REJECT")
                                .put("actionDigest", action.actionDigest()).put("approver", "demo-operator")
                                .put("decidedAt", Instant.now().toString());
                        store.timeline(r.runId, "APPROVAL", null, null, null, "Rejected by demo-operator", r.approval);
                        workflow.transition(r, RunState.COMPLETED, Outcome.ACTION_REJECTED, "Proposed action rejected");
                    }
                    default -> throw new Problems.ApiException(422, "INVALID_REQUEST", "decision must be APPROVE or REJECT");
                }
            }
            return r;
        });
    }

    public Run cancel(UUID runId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run r = store.lock(runId).orElseThrow(() -> new Problems.ApiException(404, "NOT_FOUND", "No such run"));
            if (!r.state.terminal()) {
                try (Scope ignored = workflow.runTrace(r, "run.cancel")) {
                    workflow.transition(r, RunState.CANCELLED, Outcome.CANCELLED_BY_OPERATOR, "Cancelled by operator");
                }
            }
            return r;
        });
    }

    static final List<TriagePlan.PlannedTask> PRESET_TASKS = List.of(
            new TriagePlan.PlannedTask(Topic.DEPLOYMENT_HISTORY, "Which releases of the service happened in the last hour?"),
            new TriagePlan.PlannedTask(Topic.CONFIG_DIFF, "What changed between the last two versions?"),
            new TriagePlan.PlannedTask(Topic.ERROR_SUMMARY, "Which endpoints and error types increased, and when?"));

    /** Bounded load: N preset-planned evidence runs (no triage model call), dispatched at once. */
    public com.fasterxml.jackson.databind.node.ObjectNode startBurst(Integer requested) {
        int n = Math.clamp(requested == null ? burstMaxRuns : requested, 1, burstMaxRuns);
        return QuarkusTransaction.requiringNew().call(() -> {
            if (store.activeBurst().isPresent()) {
                throw new Problems.ApiException(409, "BURST_ACTIVE", "A burst is already running");
            }
            admission(n);
            UUID burstId = Ids.v7();
            store.insertBurst(burstId, n);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                CreateRunRequest req = new CreateRunRequest("Load run " + (i + 1) + "/" + n + ": checkout errors",
                        "Bounded load demonstration: preset evidence plan for the checkout incident.", "checkout",
                        Scenario.BURST, RemediationMode.PROPOSE_ONLY, FaultInjection.NONE, Map.of("burst", burstId.toString()));
                Run r = newRun(Ids.v7(), req, PlanSource.PRESET, burstId);
                store.insert(r);
                workflow.startEvidence(r, new TriagePlan(null, null, 0, null, PRESET_TASKS), "preset plan (load run)");
                Counter.builder("maop.runs.started").tag("scenario", Scenario.BURST.name()).register(registry).increment();
                ids.add(r.runId);
            }
            var b = store.activeBurst().orElseThrow();
            outbox.event(EventTypes.eventSubject(EventTypes.BURST_CHANGED),
                    CloudEvent.builder(Workflow.SERVICE, EventTypes.BURST_CHANGED).data(b).build());
            b.set("runIds", Json.tree(ids));
            return b;
        });
    }

    public com.fasterxml.jackson.databind.node.ObjectNode currentBurst() {
        return QuarkusTransaction.requiringNew().call(() -> store.activeBurst().orElseGet(() -> {
            var n = Json.MAPPER.createObjectNode();
            n.put("active", false);
            n.putNull("burstId");
            n.putNull("runs");
            n.putNull("completed");
            n.putNull("startedAt");
            return n;
        }));
    }

    /** Stop load: cancel the burst's active runs and purge queued evidence work. Idempotent. */
    public com.fasterxml.jackson.databind.node.ObjectNode stopBurst() throws Exception {
        var result = QuarkusTransaction.requiringNew().call(() -> {
            var b = store.activeBurst();
            if (b.isEmpty()) {
                return currentBurst();
            }
            UUID burstId = UUID.fromString(b.get().get("burstId").asText());
            for (UUID id : store.activeRunsOfBurst(burstId)) {
                Run r = store.lock(id).orElseThrow();
                workflow.transition(r, RunState.CANCELLED, Outcome.CANCELLED_BY_OPERATOR, "Load stopped by operator");
            }
            store.endBurst(burstId);
            var n = b.get();
            n.put("active", false);
            outbox.event(EventTypes.eventSubject(EventTypes.BURST_CHANGED),
                    CloudEvent.builder(Workflow.SERVICE, EventTypes.BURST_CHANGED).data(n).build());
            return n;
        });
        js.purgeSubject(Subjects.WORK_STREAM, Subjects.WORK_EVIDENCE);
        return result;
    }

    /** Delete demonstration state only: runs, timeline, idempotency keys, queued messages. */
    public void reset() throws Exception {
        if (store.activeBurstPresent()) {
            throw new Problems.ApiException(409, "RESET_REFUSED", "Stop the active load before resetting");
        }
        QuarkusTransaction.requiringNew().run(store::reset);
        js.purgeStream(Subjects.WORK_STREAM);
        js.purgeStream(Subjects.DLQ_STREAM);
        js.purgeStream(Subjects.EVENTS_STREAM);
        js.publishQuietly(EventTypes.eventSubject(EventTypes.DEMO_RESET),
                CloudEvent.builder(Workflow.SERVICE, EventTypes.DEMO_RESET).data(Map.of()).build());
    }
}
