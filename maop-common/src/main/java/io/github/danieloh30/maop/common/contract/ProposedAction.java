package io.github.danieloh30.maop.common.contract;

import java.util.Map;

public record ProposedAction(ActionType actionType, String target, Map<String, Object> parameters, boolean sideEffect, String actionDigest) {
}
