package io.github.danieloh30.maop.common.model;

public record Classification(Category category, Severity severity, double confidence, String rationale) {
}
