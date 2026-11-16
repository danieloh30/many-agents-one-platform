package io.github.danieloh30.maop.common.contract;

public record Classification(Category category, Severity severity, double confidence, String rationale) {
}
