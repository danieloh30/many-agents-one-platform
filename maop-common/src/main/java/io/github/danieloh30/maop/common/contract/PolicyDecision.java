package io.github.danieloh30.maop.common.contract;

import java.util.List;

public record PolicyDecision(boolean allow, String decisionId, String policyRevision, List<String> reasons) {
}
