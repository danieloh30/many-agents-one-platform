package io.github.danieloh30.maop.triage;

/** POST /v1/runs/{id}/approval body. decision is APPROVE or REJECT. */
public record ApprovalRequest(String decision, String actionDigest, String comment) {
}
