package io.github.danieloh30.maop.recommendation;

import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.Deadlines;
import io.github.danieloh30.maop.common.RunContext;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.contract.ActionCommand;
import io.github.danieloh30.maop.common.contract.ActionCompleted;
import io.github.danieloh30.maop.common.contract.ExecutionAttempt;
import io.github.danieloh30.maop.common.contract.FailureReason;
import io.github.danieloh30.maop.common.contract.ProposedAction;
import io.github.danieloh30.maop.common.contract.Recommendation;
import io.github.danieloh30.maop.common.contract.RecommendationCommand;
import io.github.danieloh30.maop.common.contract.RecommendationCompleted;
import io.github.danieloh30.maop.common.contract.RemediationMode;
import io.github.danieloh30.maop.common.contract.TaskKind;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.messaging.Disposition;
import io.github.danieloh30.maop.common.messaging.InboundMessage;
import io.github.danieloh30.maop.common.messaging.MessageHandler;
import io.github.danieloh30.maop.common.messaging.WorkerSupport;
import io.github.danieloh30.maop.common.tools.ToolClient;
import io.github.danieloh30.maop.common.tools.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Consumes task.recommendation.requested and task.action.requested. */
@ApplicationScoped
public class RecommendationHandler implements MessageHandler {

    @Inject
    RecommendationAgent agent;
    @Inject
    WorkerSupport worker;
    @Inject
    ToolClient tools;

    @Override
    public Disposition handle(InboundMessage m) throws Exception {
        try {
            return switch (m.event().typeSuffix()) {
                case EventTypes.RECOMMENDATION_REQUESTED -> recommend(m);
                case EventTypes.ACTION_REQUESTED -> act(m);
                default -> Disposition.ACK;
            };
        } finally {
            RunContext.clear();
        }
    }

    private Disposition recommend(InboundMessage m) throws Exception {
        RecommendationCommand cmd = m.event().dataAs(RecommendationCommand.class);
        worker.started(m.event(), TaskKind.RECOMMENDATION, m.delivery());
        BudgetTracker budget = new BudgetTracker(cmd.budget());
        try {
            if (Deadlines.passed(cmd.deadline())) {
                throw TaskFailure.deadline("recommendation");
            }
            Recommendation rec = agent.propose(cmd, budget);
            ExecutionAttempt attempt = null;
            if (cmd.remediationMode() == RemediationMode.ATTEMPT_UNAPPROVED && rec.proposedAction().sideEffect()) {
                // Demonstration of the protected boundary: try the simulated remediation WITHOUT an approval.
                ToolResult r = tools.invoke(cmd.runId(), cmd.taskId(), rec.proposedAction().actionType().toolName(),
                        arguments(rec.proposedAction()), null, cmd.deadline(), budget);
                attempt = new ExecutionAttempt(r.invocationId(), r.status(), r.decision() == null ? null : r.decision().decisionId(),
                        r.decision() == null ? List.of() : r.decision().reasons());
            }
            worker.result(EventTypes.RECOMMENDATION_COMPLETED, cmd.runId(), cmd.taskId(),
                    new RecommendationCompleted(cmd.taskId(), rec, attempt, budget.usage(), m.delivery()));
            return Disposition.ACK;
        } catch (TaskFailure f) {
            if (f.retryable()) {
                throw f;
            }
            worker.failed(cmd.runId(), cmd.taskId(), TaskKind.RECOMMENDATION, f, m.delivery(), budget.usage());
            return Disposition.ACK;
        }
    }

    private Disposition act(InboundMessage m) throws Exception {
        ActionCommand cmd = m.event().dataAs(ActionCommand.class);
        worker.started(m.event(), TaskKind.ACTION, m.delivery());
        BudgetTracker budget = new BudgetTracker(cmd.budget());
        try {
            ToolResult r = tools.invoke(cmd.runId(), cmd.taskId(), cmd.proposedAction().actionType().toolName(),
                    arguments(cmd.proposedAction()), cmd.approvalId(), cmd.deadline(), budget);
            if (r.denied()) {
                throw new TaskFailure(FailureReason.TOOL_DENIED, false,
                        "Policy denied the approved action: " + String.join(", ", r.decision().reasons()));
            }
            worker.result(EventTypes.ACTION_COMPLETED, cmd.runId(), cmd.taskId(),
                    new ActionCompleted(cmd.taskId(), r.invocationId(), r.status(), r.replayed(), budget.usage(), m.delivery()));
            return Disposition.ACK;
        } catch (TaskFailure f) {
            if (f.retryable()) {
                throw f;
            }
            worker.failed(cmd.runId(), cmd.taskId(), TaskKind.ACTION, f, m.delivery(), budget.usage());
            return Disposition.ACK;
        }
    }

    static Map<String, Object> arguments(ProposedAction a) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("service", a.target());
        if (a.parameters() != null) {
            args.putAll(a.parameters());
        }
        return args;
    }

    @Override
    public void onFinalFailure(InboundMessage m, Exception failure) throws Exception {
        TaskKind kind = EventTypes.ACTION_REQUESTED.equals(m.event().typeSuffix()) ? TaskKind.ACTION : TaskKind.RECOMMENDATION;
        worker.deadLetter(m, kind, failure);
    }
}
