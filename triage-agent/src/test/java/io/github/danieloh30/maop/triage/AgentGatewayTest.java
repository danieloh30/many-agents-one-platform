package io.github.danieloh30.maop.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.agentic.AgentErrors;
import io.github.danieloh30.maop.common.agentic.AgentInvocation;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.common.model.TaskBudget;
import io.github.danieloh30.maop.common.model.TriagePlan;
import io.github.danieloh30.maop.triage.agentic.IncidentTriageAgent;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The declarative @Agent is a CDI bean whose model calls go through ModelGateway (budget, deadline, metrics). */
@QuarkusTest
@QuarkusTestResource(value = TriageResources.class, restrictToAnnotatedClass = true)
class AgentGatewayTest {

    @Inject
    IncidentTriageAgent agent;
    @Inject
    MeterRegistry registry;

    private double calls() {
        return registry.find("maop.model.calls").tag("purpose", "triage.classify").tag("status", "ok").counters()
                .stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void agentCallUsesTheGatewayAndTheTaskBudget() {
        double before = calls();
        BudgetTracker budget = new BudgetTracker(new TaskBudget(1, 0, 800));
        TriagePlan plan = AgentInvocation.call(Instant.now().plusSeconds(60), budget,
                () -> agent.classify("Checkout errors", "checkout", "5xx after release", 4, ""));
        assertEquals(3, plan.tasks().size());
        assertTrue(plan.rationale().startsWith("[deterministic]"), "labelled deterministic substitute in tests");
        assertEquals(1, budget.usage().modelCalls(), "the call counted against the task budget");
        assertEquals(before + 1, calls(), "metrics recorded by ModelGateway for purpose triage.classify");

        RuntimeException e = assertThrows(RuntimeException.class, () -> AgentInvocation.call(Instant.now().plusSeconds(60),
                budget, () -> agent.classify("again", "checkout", "budget is spent", 4, "")));
        TaskFailure tf = AgentErrors.taskFailure(e);
        assertEquals(FailureReason.BUDGET_EXHAUSTED, tf.reason(), "budget enforced inside the agent call");
    }

    @Test
    void agentRefusesToCallTheModelPastTheDeadline() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> AgentInvocation.call(Instant.now().plusSeconds(1),
                new BudgetTracker(new TaskBudget(2, 0, 800)), () -> agent.classify("late", "checkout", "too late", 4, "")));
        assertEquals(FailureReason.DEADLINE_EXCEEDED, AgentErrors.taskFailure(e).reason());
    }
}
