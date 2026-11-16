package io.github.danieloh30.maop.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.contract.ActionDigests;
import io.github.danieloh30.maop.common.contract.ApprovalGrant;
import io.github.danieloh30.maop.common.contract.PolicyDecision;
import io.github.danieloh30.maop.common.contract.ToolInvocationRequest;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tools.ToolCatalog;
import io.github.danieloh30.maop.common.tools.ToolCatalog.Tool;
import io.github.danieloh30.maop.common.tools.ToolCatalog.ToolClass;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.github.danieloh30.maop.outbox.Outbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The enforcement point (docs/security.md section 4.1): idempotency claim, deadline check, trusted policy input,
 * OPA decision (fail closed), audit, and only then the (simulated) effect — all in one transaction.
 */
@ApplicationScoped
public class ToolInvocationService {

    public sealed interface Outcome {
        record Executed(ObjectNode body) implements Outcome {
        }

        record Denied(ObjectNode body) implements Outcome {
        }

        record Rejected(int status, String code, String detail) implements Outcome {
        }
    }

    static final class Abort extends RuntimeException {
        final Outcome.Rejected rejection;

        Abort(int status, String code, String detail) {
            super(detail, null, false, false);
            this.rejection = new Outcome.Rejected(status, code, detail);
        }
    }

    @Inject
    InvocationStore store;
    @Inject
    OpaClient opa;
    @Inject
    SyntheticTools synthetic;
    @Inject
    Outbox outbox;
    @Inject
    MeterRegistry registry;

    public Outcome invoke(String toolName, String idempotencyKey, ToolInvocationRequest req, VerifiedCaller caller) {
        Optional<Tool> found = ToolCatalog.find(toolName);
        if (found.isEmpty()) {
            return new Outcome.Rejected(404, "UNKNOWN_TOOL", "No tool named " + toolName);
        }
        Tool tool = found.get();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 300) {
            return new Outcome.Rejected(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header (max 300 chars) is required");
        }
        if (req == null || req.runId() == null || req.taskId() == null) {
            return new Outcome.Rejected(422, "INVALID_REQUEST", "runId and taskId are required");
        }
        Map<String, Object> args = req.arguments() == null ? Map.of() : req.arguments();
        String invalid = ToolCatalog.validate(tool, args);
        if (invalid != null) {
            return new Outcome.Rejected(422, "INVALID_ARGUMENTS", invalid);
        }
        Map<String, Object> digestInput = new LinkedHashMap<>();
        digestInput.put("arguments", args);
        digestInput.put("approvalId", req.approvalId() == null ? null : req.approvalId().toString());
        String argsDigest = Json.digest(digestInput);
        Span.current().setAttribute("maop.tool.name", toolName);
        Span.current().setAttribute("maop.run.id", req.runId().toString());
        try {
            return QuarkusTransaction.requiringNew().call(() -> doInvoke(tool, idempotencyKey, req, args, argsDigest, caller));
        } catch (Abort a) {
            count(toolName, a.rejection.code().equals("POLICY_UNAVAILABLE") ? "unavailable" : "rejected");
            return a.rejection;
        }
    }

    private Outcome doInvoke(Tool tool, String key, ToolInvocationRequest req, Map<String, Object> args,
            String argsDigest, VerifiedCaller caller) throws Exception {
        UUID invocationId = Ids.v7();
        boolean owner = store.claim(invocationId, key, argsDigest, req.runId(), req.taskId(), tool.name(),
                tool.toolClass().name(), caller, Json.tree(args), req.approvalId());
        if (!owner) {
            InvocationStore.Stored s = store.lockByKey(key).orElseThrow();
            if (!s.callerSpiffeId().equals(caller.spiffeId()) || !s.argsDigest().equals(argsDigest)
                    || !s.toolName().equals(tool.name())) {
                throw new Abort(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency key already used for a different request");
            }
            store.countReplay(s.invocationId());
            ObjectNode body = response(s.invocationId(), tool, s.status(), true, s.decision(), caller, s.result());
            emit(req, tool, s.invocationId(), "REPLAYED", s.decision(), caller);
            count(tool.name(), "replayed");
            Span.current().setAttribute("maop.tool.replayed", true);
            return "DENIED".equals(s.status()) ? new Outcome.Denied(problemFor(body)) : new Outcome.Executed(body);
        }
        if (req.deadline() != null && Instant.now().isAfter(req.deadline())) {
            throw new Abort(504, "DEADLINE_EXCEEDED", "Run deadline has passed; nothing was executed");
        }
        ObjectNode input = policyInput(caller, "tool.invoke", tool, req, args);
        PolicyDecision d;
        try {
            d = opa.decide(input);
        } catch (OpaClient.PolicyUnavailableException e) {
            throw new Abort(503, "POLICY_UNAVAILABLE", "Authorization unavailable (" + e.getMessage() + "); failing closed");
        }
        if (!d.allow()) {
            store.complete(invocationId, "DENIED", d, null, 0);
            emit(req, tool, invocationId, "DENIED", d, caller);
            count(tool.name(), "denied");
            return new Outcome.Denied(problemFor(response(invocationId, tool, "DENIED", false, d, caller, null)));
        }
        JsonNode result = execute(tool, invocationId, req, args);
        store.complete(invocationId, "EXECUTED", d, result, 1);
        emit(req, tool, invocationId, "EXECUTED", d, caller);
        count(tool.name(), "executed");
        return new Outcome.Executed(response(invocationId, tool, "EXECUTED", false, d, caller, result));
    }

    private JsonNode execute(Tool tool, UUID invocationId, ToolInvocationRequest req, Map<String, Object> args)
            throws Exception {
        Span span = Tracing.tracer().spanBuilder("tool.execute " + tool.name())
                .setAttribute("maop.tool.name", tool.name()).setAttribute("maop.tool.class", tool.toolClass().name())
                .setAttribute("maop.tool.simulated", true).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (tool.toolClass() == ToolClass.READ) {
                return synthetic.execute(tool.name(), args, Instant.now());
            }
            Map<String, Object> params = new LinkedHashMap<>(args);
            String target = String.valueOf(params.remove("service"));
            UUID actionId = Ids.v7();
            store.insertSimulatedAction(actionId, invocationId, req.runId(), tool.actionType().name(), target,
                    Json.tree(params));
            ObjectNode r = Json.MAPPER.createObjectNode();
            r.put("simulated", true);
            r.put("actionId", actionId.toString());
            r.put("actionType", tool.actionType().name());
            r.put("target", target);
            r.set("parameters", Json.tree(params));
            r.put("note", "Simulation only: the intent was recorded; no cluster or system was changed.");
            return r;
        } finally {
            span.end();
        }
    }

    ObjectNode policyInput(VerifiedCaller caller, String action, Tool tool, ToolInvocationRequest req,
            Map<String, Object> args) throws Exception {
        ObjectNode in = Json.MAPPER.createObjectNode();
        ObjectNode c = in.putObject("caller");
        c.put("spiffe_id", caller.spiffeId());
        c.put("trust_domain", caller.trustDomain());
        in.put("action", action);
        if (tool != null) {
            ObjectNode t = in.putObject("tool");
            t.put("name", tool.name());
            t.put("class", tool.toolClass().name());
            t.put("simulated", true); // every tool in this service is synthetic or simulated
        }
        if (req != null) {
            in.putObject("run").put("id", req.runId().toString()).put("deadline",
                    req.deadline() == null ? null : req.deadline().toString());
            ObjectNode r = in.putObject("request");
            r.put("args_digest", Json.digest(args));
            if (tool != null && tool.actionType() != null) {
                Map<String, Object> params = new LinkedHashMap<>(args);
                String target = String.valueOf(params.remove("service"));
                r.put("action_digest", ActionDigests.digest(req.runId(), tool.actionType(), target, params));
            }
            if (req.approvalId() != null) {
                Optional<ApprovalGrant> g = store.approval(req.approvalId());
                if (g.isPresent()) {
                    ObjectNode a = in.putObject("approval");
                    a.put("id", req.approvalId().toString());
                    a.put("run_id", g.get().runId().toString());
                    a.put("action_digest", g.get().actionDigest());
                    a.put("approver", g.get().approver());
                    a.put("expires_at", g.get().expiresAt().toString());
                } else {
                    in.putNull("approval");
                }
            } else {
                in.putNull("approval");
            }
        } else {
            in.putNull("approval");
        }
        in.put("now", Instant.now().toString());
        return in;
    }

    private ObjectNode response(UUID invocationId, Tool tool, String status, boolean replayed, PolicyDecision d,
            VerifiedCaller caller, JsonNode result) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("invocationId", invocationId.toString());
        n.put("toolName", tool.name());
        n.put("toolClass", tool.toolClass().name());
        n.put("status", status);
        n.put("replayed", replayed);
        n.put("simulated", true);
        n.put("dataOrigin", "SYNTHETIC");
        n.set("decision", Json.tree(d));
        n.set("caller", Json.tree(caller.toCaller()));
        n.set("result", result);
        n.put("executedAt", "EXECUTED".equals(status) ? Instant.now().toString() : null);
        return n;
    }

    private ObjectNode problemFor(ObjectNode response) {
        ObjectNode p = Problems.body(403, "POLICY_DENIED", "Policy denied the request before execution: "
                + String.join(", ", Json.convert(response.get("decision"), PolicyDecision.class).reasons()));
        p.setAll(response);
        return p;
    }

    private void emit(ToolInvocationRequest req, Tool tool, UUID invocationId, String status, PolicyDecision d,
            VerifiedCaller caller) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invocationId", invocationId.toString());
        data.put("runId", req.runId().toString());
        data.put("taskId", req.taskId().toString());
        data.put("toolName", tool.name());
        data.put("toolClass", tool.toolClass().name());
        data.put("status", status);
        data.put("callerSpiffeId", caller.spiffeId());
        data.put("decisionId", d.decisionId());
        data.put("reasons", d.reasons() == null ? List.of() : d.reasons());
        data.put("simulated", true);
        CloudEvent e = CloudEvent.builder("tool-service", EventTypes.TOOL_INVOCATION_COMPLETED)
                .task(req.runId(), req.taskId()).data(data).build();
        outbox.event(EventTypes.eventSubject(EventTypes.TOOL_INVOCATION_COMPLETED), e);
    }

    private void count(String tool, String decision) {
        Counter.builder("maop.tool.invocations").tag("tool", tool).tag("decision", decision)
                .description("Tool invocations by result").register(registry).increment();
    }

    /** Authorization for non-tool actions (approval.register, audit.read, admin.reset, tools.list). */
    public Optional<Outcome.Rejected> authorize(VerifiedCaller caller, String action) {
        try {
            PolicyDecision d = opa.decide(policyInput(caller, action, null, null, null));
            if (!d.allow()) {
                return Optional.of(new Outcome.Rejected(403, "POLICY_DENIED",
                        "Policy denied " + action + ": " + String.join(", ", d.reasons())));
            }
            return Optional.empty();
        } catch (OpaClient.PolicyUnavailableException e) {
            return Optional.of(new Outcome.Rejected(503, "POLICY_UNAVAILABLE", "Authorization unavailable; failing closed"));
        } catch (Exception e) {
            return Optional.of(new Outcome.Rejected(500, "INTERNAL", e.getMessage()));
        }
    }
}
