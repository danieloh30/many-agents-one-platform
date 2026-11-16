package io.github.danieloh30.maop.common.model;

public record Usage(int modelCalls, int toolCalls, long inputTokens, long outputTokens, int policyDenials) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0, 0);

    public Usage plus(Usage o) {
        if (o == null) {
            return this;
        }
        return new Usage(modelCalls + o.modelCalls, toolCalls + o.toolCalls, inputTokens + o.inputTokens,
                outputTokens + o.outputTokens, policyDenials + o.policyDenials);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
