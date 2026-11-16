package io.github.danieloh30.maop.triage.agentic;

import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.agentic.AgentErrors;
import io.github.danieloh30.maop.common.agentic.AgentInvocation;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.common.model.Incident;
import io.github.danieloh30.maop.common.model.TaskBudget;
import io.github.danieloh30.maop.common.model.Topic;
import io.github.danieloh30.maop.common.model.TriagePlan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Runs the {@link IncidentTriageAgent} under the run's deadline and a two-call budget (one call plus at most one
 * repair), then applies the domain rules the plan must satisfy.
 */
@ApplicationScoped
public class TriagePlanner {

    public static final String PURPOSE = "triage.classify";

    @Inject
    IncidentTriageAgent agent;

    public record Result(TriagePlan plan, BudgetTracker budget, String truncation) {
    }

    public Result plan(Incident incident, int maxTasks, Instant deadline) {
        BudgetTracker budget = new BudgetTracker(new TaskBudget(2, 0, 800));
        String feedback = "";
        String lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String fb = feedback;
            TriagePlan p;
            try {
                p = AgentInvocation.call(deadline, budget,
                        () -> agent.classify(incident.title(), incident.service(), incident.description(), maxTasks, fb));
            } catch (RuntimeException e) {
                TaskFailure tf = AgentErrors.taskFailure(e);
                if (tf != null) {
                    throw tf;
                }
                lastError = "output was not a valid triage plan (" + e.getClass().getSimpleName() + ")";
                feedback = AgentErrors.repairFeedback(lastError);
                continue;
            }
            try {
                validate(p);
            } catch (IllegalArgumentException e) {
                lastError = e.getMessage();
                feedback = AgentErrors.repairFeedback(lastError);
                continue;
            }
            String truncation = null;
            if (p.tasks().size() > maxTasks) {
                truncation = "plan truncated from " + p.tasks().size() + " to " + maxTasks + " tasks (budget)";
                p = new TriagePlan(p.category(), p.severity(), p.confidence(), p.rationale(), p.tasks().subList(0, maxTasks));
            }
            return new Result(p, budget, truncation);
        }
        throw new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false, "Triage output invalid after repair: " + lastError);
    }

    /** Parse model text into a plan and validate it (used for raw text, e.g. in tests). */
    public static TriagePlan parse(String text) {
        String json = Json.extractObject(text);
        if (json == null) {
            throw new IllegalArgumentException("no JSON object found");
        }
        TriagePlan p = Json.read(json, TriagePlan.class);
        validate(p);
        return p;
    }

    public static void validate(TriagePlan p) {
        if (p == null || p.category() == null || p.severity() == null) {
            throw new IllegalArgumentException("category and severity are required");
        }
        if (p.confidence() < 0 || p.confidence() > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (p.rationale() == null || p.rationale().isBlank() || p.rationale().length() > 400) {
            throw new IllegalArgumentException("rationale is required (max 400 chars)");
        }
        if (p.tasks() == null || p.tasks().isEmpty()) {
            throw new IllegalArgumentException("at least one task is required");
        }
        Set<Topic> seen = EnumSet.noneOf(Topic.class);
        for (TriagePlan.PlannedTask t : p.tasks()) {
            if (t.topic() == null || !seen.add(t.topic())) {
                throw new IllegalArgumentException("task topics must be valid and distinct");
            }
            if (t.question() == null || t.question().isBlank() || t.question().length() > 300) {
                throw new IllegalArgumentException("each task needs a question (max 300 chars)");
            }
        }
    }
}
