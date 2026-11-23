package io.github.danieloh30.maop.triage.service;

import io.github.danieloh30.maop.triage.agentic.TriagePlanner;
import io.github.danieloh30.maop.triage.model.Run;

import io.github.danieloh30.maop.common.Deadlines;
import io.github.danieloh30.maop.common.RunContext;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.model.AnalysisMode;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.common.model.Outcome;
import io.github.danieloh30.maop.common.model.RunState;
import io.github.danieloh30.maop.common.model.TriageCommand;
import io.github.danieloh30.maop.common.messaging.Disposition;
import io.github.danieloh30.maop.common.messaging.InboundMessage;
import io.github.danieloh30.maop.common.messaging.MessageHandler;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Consumes run.triage.requested. Idempotent through state guards plus optimistic locking: a duplicate delivery
 * either finds the run past TRIAGING (ack) or loses the final versioned update (ack).
 */
@ApplicationScoped
public class TriageWorker implements MessageHandler {

    private static final Logger LOG = Logger.getLogger(TriageWorker.class);

    @Inject
    RunStore store;
    @Inject
    Workflow workflow;
    @Inject
    TriagePlanner planner;

    @Override
    public Disposition handle(InboundMessage m) {
        TriageCommand cmd = m.event().dataAs(TriageCommand.class);
        RunContext.set(cmd.runId(), null);
        try {
            Optional<Run> start = QuarkusTransaction.requiringNew().call(() -> {
                Optional<Run> o = store.lock(cmd.runId());
                if (o.isEmpty()) {
                    return Optional.<Run>empty();
                }
                Run r = o.get();
                if (r.state != RunState.SUBMITTED && r.state != RunState.TRIAGING) {
                    return Optional.<Run>empty();
                }
                if (Deadlines.passed(r.deadline)) {
                    workflow.transition(r, RunState.TIMED_OUT, Outcome.DEADLINE_EXCEEDED, "Deadline passed before triage");
                    return Optional.<Run>empty();
                }
                if (r.state == RunState.SUBMITTED) {
                    workflow.transition(r, RunState.TRIAGING, null, "Classifying incident");
                }
                return Optional.of(r);
            });
            if (start.isEmpty()) {
                return Disposition.ACK;
            }
            Run snapshot = start.get();
            int maxTasks = Math.min(snapshot.budget.maxEvidenceTasks(),
                    Math.max(0, (snapshot.budget.maxModelCalls() - 2 - Workflow.RECOMMENDATION_BUDGET.maxModelCalls())
                            / Workflow.EVIDENCE_BUDGET.maxModelCalls()))
                    // One evidence slot is reserved for the code-planned sandboxed analysis task.
                    - (snapshot.analysisMode == AnalysisMode.SANDBOXED_SCRIPT ? 1 : 0);
            TriagePlanner.Result result;
            try {
                if (maxTasks < 1) {
                    throw TaskFailure.budget("model-call budget too small to delegate any evidence task");
                }
                result = planner.plan(snapshot.incident, maxTasks, snapshot.deadline);
            } catch (TaskFailure f) {
                if (f.retryable()) {
                    throw f;
                }
                end(cmd, Outcome.from(f.reason()), f.getMessage());
                return Disposition.ACK;
            }
            QuarkusTransaction.requiringNew().run(() -> {
                Run r = store.lock(cmd.runId()).orElseThrow();
                if (r.state != RunState.TRIAGING) {
                    LOG.debugf("Run %s already advanced by another delivery", r.runId);
                    return;
                }
                r.usage = r.usage.plus(result.budget().usage());
                workflow.startEvidence(r, result.plan(), result.truncation());
            });
            return Disposition.ACK;
        } finally {
            RunContext.clear();
        }
    }

    private void end(TriageCommand cmd, Outcome outcome, String reason) {
        QuarkusTransaction.requiringNew().run(() -> store.lock(cmd.runId()).ifPresent(r -> {
            if (!r.state.terminal()) {
                workflow.transition(r, RunState.NEEDS_REVIEW, outcome, reason);
            }
        }));
    }

    @Override
    public void onFinalFailure(InboundMessage m, Exception failure) {
        TriageCommand cmd = m.event().dataAs(TriageCommand.class);
        Outcome outcome = failure instanceof TaskFailure tf ? Outcome.from(tf.reason()) : Outcome.from(FailureReason.INTERNAL);
        end(cmd, outcome, "Triage failed after " + m.delivery() + " deliveries: " + failure.getMessage());
    }
}
