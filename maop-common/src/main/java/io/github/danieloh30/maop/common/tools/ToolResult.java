package io.github.danieloh30.maop.common.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.model.PolicyDecision;

/** Outcome of a tool invocation as seen by the caller: executed (possibly replayed) or denied by policy. */
public record ToolResult(String invocationId, String toolName, String status, boolean replayed,
        PolicyDecision decision, JsonNode result) {

    public boolean denied() {
        return "DENIED".equals(status);
    }
}
