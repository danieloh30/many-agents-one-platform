package io.github.danieloh30.maop.triage;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.contract.FailureReason;
import io.github.danieloh30.maop.common.contract.Incident;
import io.github.danieloh30.maop.common.contract.TaskBudget;
import io.github.danieloh30.maop.common.contract.Topic;
import io.github.danieloh30.maop.common.contract.TriagePlan;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.model.ModelCall;
import io.github.danieloh30.maop.common.model.ModelGateway;
import io.github.danieloh30.maop.common.model.Purposes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Classifies the incident and plans evidence tasks with one bounded model call (plus at most one repair). */
@ApplicationScoped
public class TriagePlanner {

    public static final String PURPOSE = "triage.classify";

    static final String SYSTEM = Purposes.marker(PURPOSE) + """

            You are the triage agent of an incident-response platform. Classify the incident and plan the evidence
            to collect. Respond with ONLY a JSON object, no prose, matching:
            {"category": one of RELEASE_REGRESSION|DEPENDENCY_FAILURE|CAPACITY|CONFIGURATION|UNKNOWN,
             "severity": one of SEV1|SEV2|SEV3|SEV4,
             "confidence": number 0..1,
             "rationale": "one or two sentences for an operator (max 300 chars)",
             "tasks": [{"topic": one of SERVICE_CATALOG|DEPLOYMENT_HISTORY|CONFIG_DIFF|ERROR_SUMMARY,
                        "question": "what the evidence worker should find out (max 200 chars)"}]}
            Plan between 1 and %d tasks with distinct topics. Do not include reasoning steps.""";

    @Inject
    ModelGateway model;

    public record Result(TriagePlan plan, BudgetTracker budget, String truncation) {
    }

    public Result plan(Incident incident, int maxTasks, Instant deadline) {
        BudgetTracker budget = new BudgetTracker(new TaskBudget(2, 0, 800));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM.formatted(maxTasks)));
        messages.add(UserMessage.from("Incident title: " + incident.title() + "\nAffected service: " + incident.service()
                + "\nDescription: " + incident.description()));
        String lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ModelCall call = model.call(PURPOSE, ChatRequest.builder().messages(messages).maxOutputTokens(800).build(),
                    deadline, budget);
            AiMessage ai = call.response().aiMessage();
            try {
                TriagePlan p = parse(ai.text());
                String truncation = null;
                if (p.tasks().size() > maxTasks) {
                    truncation = "plan truncated from " + p.tasks().size() + " to " + maxTasks + " tasks (budget)";
                    p = new TriagePlan(p.category(), p.severity(), p.confidence(), p.rationale(), p.tasks().subList(0, maxTasks));
                }
                return new Result(p, budget, truncation);
            } catch (IllegalArgumentException e) {
                lastError = e.getMessage();
                messages.add(ai);
                messages.add(UserMessage.from("Your previous answer was invalid: " + lastError
                        + ". Reply again with ONLY the corrected JSON object."));
            }
        }
        throw new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false, "Triage output invalid after repair: " + lastError);
    }

    static TriagePlan parse(String text) {
        String json = Json.extractObject(text);
        if (json == null) {
            throw new IllegalArgumentException("no JSON object found");
        }
        TriagePlan p = Json.read(json, TriagePlan.class);
        if (p.category() == null || p.severity() == null) {
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
        return p;
    }
}
