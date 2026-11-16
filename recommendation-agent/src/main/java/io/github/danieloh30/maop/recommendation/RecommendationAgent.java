package io.github.danieloh30.maop.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.contract.ActionDigests;
import io.github.danieloh30.maop.common.contract.ActionType;
import io.github.danieloh30.maop.common.contract.EvidenceItem;
import io.github.danieloh30.maop.common.contract.FailureReason;
import io.github.danieloh30.maop.common.contract.ProposedAction;
import io.github.danieloh30.maop.common.contract.Recommendation;
import io.github.danieloh30.maop.common.contract.RecommendationCommand;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.model.ModelGateway;
import io.github.danieloh30.maop.common.model.Purposes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Turns collected evidence into one structured, evidence-backed proposed action (with at most one repair). */
@ApplicationScoped
public class RecommendationAgent {

    public static final String PURPOSE = "recommendation.propose";

    static final String SYSTEM = Purposes.marker(PURPOSE) + """

            You are the recommendation agent of an incident-response platform. Propose ONE next action supported by
            the evidence. Any remediation is only a proposal: a human must approve it and execution is simulated.
            Respond with ONLY a JSON object:
            {"summary": "short imperative sentence (max 160 chars)",
             "explanation": "concise justification for an operator, citing the evidence (max 500 chars)",
             "proposedAction": {"actionType": one of ROLLBACK_DEPLOYMENT|SET_FEATURE_FLAG|ESCALATE_TO_HUMAN|OBSERVE,
                                "target": "service name",
                                "parameters": {} // ROLLBACK_DEPLOYMENT: {"toVersion": "x.y.z"}; SET_FEATURE_FLAG: {"flag": "name", "enabled": false}; otherwise {}
                               },
             "citations": ["evidenceId", ...],   // ids from the evidence list, at least one
             "confidence": number 0..1,
             "risks": ["short risk", ...]}       // up to 3
            Copy service names and version numbers exactly as they appear in the evidence facts; never invent a version.
            Do not include reasoning steps.""";

    @Inject
    ModelGateway model;

    public Recommendation propose(RecommendationCommand cmd, BudgetTracker budget) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("incident", cmd.incident());
        ctx.put("classification", cmd.classification());
        ctx.put("evidenceCompleteness", cmd.evidenceCompleteness());
        ctx.put("evidence", cmd.evidence().stream().map(e -> Map.of("evidenceId", e.evidenceId(), "topic", e.topic(),
                "finding", e.finding(), "facts", e.facts())).toList());
        messages.add(UserMessage.from(Json.write(ctx)));
        Set<String> evidenceIds = cmd.evidence().stream().map(EvidenceItem::evidenceId).collect(Collectors.toSet());
        String error = null;
        for (int attempt = 0; attempt < 2 && budget.usage().modelCalls() < cmd.budget().maxModelCalls(); attempt++) {
            AiMessage ai = model.call(PURPOSE, ChatRequest.builder().messages(messages)
                    .maxOutputTokens(cmd.budget().maxOutputTokens()).build(), cmd.deadline(), budget).response().aiMessage();
            try {
                return validate(cmd, ai.text(), evidenceIds);
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
                messages.add(ai);
                messages.add(UserMessage.from("Your answer was invalid: " + error + ". Reply with ONLY the corrected JSON object."));
            }
        }
        throw new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false, "Recommendation invalid: " + error);
    }

    static Recommendation validate(RecommendationCommand cmd, String text, Set<String> evidenceIds) {
        String json = Json.extractObject(text);
        if (json == null) {
            throw new IllegalArgumentException("no JSON object found");
        }
        JsonNode n = Json.parse(json);
        String summary = n.path("summary").asText("");
        String explanation = n.path("explanation").asText("");
        if (summary.isBlank() || summary.length() > 200 || explanation.isBlank() || explanation.length() > 700) {
            throw new IllegalArgumentException("summary (max 200) and explanation (max 700) are required");
        }
        JsonNode pa = n.path("proposedAction");
        ActionType type;
        try {
            type = ActionType.valueOf(pa.path("actionType").asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("proposedAction.actionType is invalid");
        }
        String target = pa.path("target").asText("");
        if (target.isBlank() || target.length() > 120) {
            throw new IllegalArgumentException("proposedAction.target is required");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode p = pa.path("parameters");
        switch (type) {
            case ROLLBACK_DEPLOYMENT -> {
                String v = p.path("toVersion").asText("");
                if (v.isBlank() || v.length() > 40) {
                    throw new IllegalArgumentException("ROLLBACK_DEPLOYMENT requires parameters.toVersion");
                }
                params.put("toVersion", v);
            }
            case SET_FEATURE_FLAG -> {
                String flag = p.path("flag").asText("");
                if (flag.isBlank() || !p.path("enabled").isBoolean()) {
                    throw new IllegalArgumentException("SET_FEATURE_FLAG requires parameters.flag and boolean parameters.enabled");
                }
                params.put("flag", flag);
                params.put("enabled", p.path("enabled").asBoolean());
            }
            default -> {
                // no parameters for actions without side effects
            }
        }
        List<String> citations = new ArrayList<>();
        n.path("citations").forEach(c -> citations.add(c.asText()));
        if (citations.isEmpty() || !evidenceIds.containsAll(citations)) {
            throw new IllegalArgumentException("citations must be non-empty and reference evidence ids " + evidenceIds);
        }
        double confidence = n.path("confidence").asDouble(-1);
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        List<String> risks = new ArrayList<>();
        n.path("risks").forEach(r -> {
            if (risks.size() < 3) {
                risks.add(r.asText().length() > 200 ? r.asText().substring(0, 200) : r.asText());
            }
        });
        ProposedAction action = ActionDigests.withDigest(cmd.runId(), new ProposedAction(type, target, params, false, null));
        return new Recommendation(summary, explanation, action, citations, confidence, risks);
    }
}
