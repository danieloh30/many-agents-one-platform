package io.github.danieloh30.maop.common.model;

import java.util.List;

public record ExecutionAttempt(String invocationId, String status, String decisionId, List<String> reasons) {
}
