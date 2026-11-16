package io.github.danieloh30.maop.recommendation.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.model.ActionDigests;
import io.github.danieloh30.maop.common.model.ActionType;
import io.github.danieloh30.maop.common.model.EvidenceItem;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.common.model.ProposedAction;
import io.github.danieloh30.maop.common.model.Recommendation;
import io.github.danieloh30.maop.common.model.RecommendationCommand;
import io.github.danieloh30.maop.common.agentic.AgentErrors;
import io.github.danieloh30.maop.common.agentic.AgentInvocation;
import io.github.danieloh30.maop.common.json.Json;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the declarative {@link RemediationAdvisorAgent} and validates its typed draft: known action type, required
 * parameters, citations that reference collected evidence; the action digest binds any later approval.
 */
@ApplicationScoped
public class RecommendationAgent {

    public static final String PURPOSE = "recommendation.propose";


    @Inject
    RemediationAdvisorAgent advisor;

    public Recommendation propose(RecommendationCommand cmd, BudgetTracker budget) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("incident", cmd.incident());
        ctx.put("classification", cmd.classification());
        ctx.put("evidenceCompleteness", cmd.evidenceCompleteness());
        ctx.put("evidence", cmd.evidence().stream().map(e -> Map.of("evidenceId", e.evidenceId(), "topic", e.topic(),
                "finding", e.finding(), "facts", e.facts())).toList());
        String context = Json.write(ctx);
        Set<String> evidenceIds = cmd.evidence().stream().map(EvidenceItem::evidenceId).collect(Collectors.toSet());
        String feedback = "";
        String error = null;
        for (int attempt = 0; attempt < 2 && budget.usage().modelCalls() < cmd.budget().maxModelCalls(); attempt++) {
            String fb = feedback;
            RecommendationDraft draft;
            try {
                draft = AgentInvocation.call(cmd.deadline(), budget, () -> advisor.propose(context, fb));
            } catch (RuntimeException e) {
                TaskFailure tf = AgentErrors.taskFailure(e);
                if (tf != null) {
                    throw tf;
                }
                error = "output could not be parsed (" + e.getClass().getSimpleName() + ")";
                feedback = AgentErrors.repairFeedback(error);
                continue;
            }
            try {
                return validate(cmd, Json.write(draft), evidenceIds);
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
                feedback = AgentErrors.repairFeedback(error);
            }
        }
        throw new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false, "Recommendation invalid: " + error);
    }

    public static Recommendation validate(RecommendationCommand cmd, String text, Set<String> evidenceIds) {
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
