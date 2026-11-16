package io.github.danieloh30.maop.common.contract;

import java.util.List;

public record ExecutionAttempt(String invocationId, String status, String decisionId, List<String> reasons) {
}
