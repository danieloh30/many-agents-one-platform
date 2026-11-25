package io.github.danieloh30.maop.triage.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.tools.ToolClient;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.github.danieloh30.maop.outbox.OutboxHandler;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

/** Registers an approval grant at tool-service over SPIFFE mTLS. Idempotent PUT, retried by the outbox relay. */
@ApplicationScoped
public class ApprovalGrantHandler implements OutboxHandler {

    public static final String KIND = "HTTP_APPROVAL_GRANT";

    @Inject
    ToolClient tools;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void deliver(UUID id, String subject, JsonNode payload) throws Exception {
        ObjectNode body = payload.deepCopy();
        String approvalId = body.remove("approvalId").asString();
        JsonNode tp = body.remove("traceparent");
        try (Scope ignored = Tracing.fromTraceparent(tp == null ? null : tp.asString()).makeCurrent()) {
            HttpResponse<String> r = tools.call("PUT", "/v1/approvals/" + approvalId, body, Instant.now().plusSeconds(10));
            if (r.statusCode() != 200 && r.statusCode() != 201) {
                throw new IllegalStateException("Approval registration returned " + r.statusCode() + ": " + r.body());
            }
        }
    }
}
