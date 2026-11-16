package io.github.danieloh30.maop.recommendation.agentic;

import java.util.List;
import java.util.Map;

/** Typed output of the remediation advisor; validated and given an action digest before it is used. */
public record RecommendationDraft(String summary, String explanation, Action proposedAction, List<String> citations,
        double confidence, List<String> risks) {

    public record Action(String actionType, String target, Map<String, Object> parameters) {
    }
}
