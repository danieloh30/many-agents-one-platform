package io.github.danieloh30.maop.common.contract;

import java.util.List;

public record Recommendation(String summary, String explanation, ProposedAction proposedAction, List<String> citations, double confidence, List<String> risks) {
}
