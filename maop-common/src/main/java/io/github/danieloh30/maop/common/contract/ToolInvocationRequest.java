package io.github.danieloh30.maop.common.contract;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ToolInvocationRequest(UUID runId, UUID taskId, Instant deadline, Map<String, Object> arguments, UUID approvalId) {
}
