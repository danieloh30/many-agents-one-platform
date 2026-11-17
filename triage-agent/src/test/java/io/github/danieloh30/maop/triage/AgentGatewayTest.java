package io.github.danieloh30.maop.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.memory.ChatMemoryAccess;
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
import java.util.ArrayList;
import java.util.List;
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
        TriagePlan plan = AgentInvocation.call(agent, Instant.now().plusSeconds(60), budget,
                id -> agent.classify(id, "Checkout errors", "checkout", "5xx after release", 4, ""));
        assertEquals(3, plan.tasks().size());
        assertTrue(plan.rationale().startsWith("[deterministic]"), "labelled deterministic substitute in tests");
        assertEquals(1, budget.usage().modelCalls(), "the call counted against the task budget");
        assertEquals(before + 1, calls(), "metrics recorded by ModelGateway for purpose triage.classify");

        RuntimeException e = assertThrows(RuntimeException.class, () -> AgentInvocation.call(agent, Instant.now().plusSeconds(60),
                budget, id -> agent.classify(id, "again", "checkout", "budget is spent", 4, "")));
        TaskFailure tf = AgentErrors.taskFailure(e);
        assertEquals(FailureReason.BUDGET_EXHAUSTED, tf.reason(), "budget enforced inside the agent call");
    }

    @Test
    void agentRefusesToCallTheModelPastTheDeadline() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> AgentInvocation.call(agent, Instant.now().plusSeconds(1),
                new BudgetTracker(new TaskBudget(2, 0, 800)), id -> agent.classify(id, "late", "checkout", "too late", 4, "")));
        assertEquals(FailureReason.DEADLINE_EXCEEDED, AgentErrors.taskFailure(e).reason());
    }

    /** Regression: a shared agent memory carried earlier tasks' conversations into every later prompt. */
    @Test
    void everyAgentCallGetsItsOwnMemoryWhichIsEvictedAfterwards() {
        assertTrue(agent instanceof ChatMemoryAccess, "eviction needs ChatMemoryAccess on the injected agent");
        ChatMemoryAccess access = (ChatMemoryAccess) agent;
        List<Object> ids = new ArrayList<>();
        for (String title : List.of("First incident", "Second incident")) {
            AgentInvocation.call(agent, Instant.now().plusSeconds(60), new BudgetTracker(new TaskBudget(1, 0, 800)), id -> {
                ids.add(id);
                TriagePlan p = agent.classify(id, title, "checkout", "5xx after release", 4, "");
                ChatMemory during = access.getChatMemory(id);
                assertTrue(during == null || during.messages().size() <= 2, "at most this call's system and user message");
                return p;
            });
        }
        assertNotEquals(ids.get(0), ids.get(1), "a fresh memory id per call");
        for (Object id : ids) {
            ChatMemory left = access.getChatMemory(id);
            assertTrue(left == null || left.messages().isEmpty(), "memory evicted after the call");
        }
    }
}
