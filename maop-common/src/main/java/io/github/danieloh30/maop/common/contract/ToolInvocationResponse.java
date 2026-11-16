package io.github.danieloh30.maop.common.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ToolInvocationResponse(String invocationId, String toolName, String toolClass, String status, boolean replayed, boolean simulated, String dataOrigin, PolicyDecision decision, Caller caller, JsonNode result, Instant executedAt) {
}
