package io.github.danieloh30.maop.common.contract;

import java.time.Instant;
import java.util.UUID;

public record ApprovalGrant(UUID runId, String actionDigest, String approver, Instant approvedAt, Instant expiresAt) {
}
