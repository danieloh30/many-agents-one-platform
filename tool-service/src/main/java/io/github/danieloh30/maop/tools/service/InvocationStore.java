package io.github.danieloh30.maop.tools.service;

import io.github.danieloh30.maop.tools.model.VerifiedCaller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.github.danieloh30.maop.common.model.ApprovalGrant;
import io.github.danieloh30.maop.common.model.PolicyDecision;
import io.github.danieloh30.maop.common.json.Json;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC access to the tools schema. All methods run in the caller's JTA transaction. */
@ApplicationScoped
public class InvocationStore {

    @Inject
    AgroalDataSource ds;

    public record Stored(UUID invocationId, String argsDigest, String callerSpiffeId, String status, String toolName,
            String toolClass, PolicyDecision decision, JsonNode result, int requests) {
    }

    /** Claim the idempotency key. Returns true when this request owns the new row. Blocks on a concurrent claimer. */
    public boolean claim(UUID invocationId, String key, String argsDigest, UUID runId, UUID taskId, String tool,
            String toolClass, VerifiedCaller caller, JsonNode args, UUID approvalId) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "insert into tool_invocation (invocation_id, idempotency_key, args_digest, run_id, task_id, tool_name, "
                        + "tool_class, status, caller_spiffe_id, caller_serial, caller_not_after, arguments, approval_id) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?::jsonb, ?) on conflict (idempotency_key) do nothing")) {
            ps.setObject(1, invocationId);
            ps.setString(2, key);
            ps.setString(3, argsDigest);
            ps.setObject(4, runId);
            ps.setObject(5, taskId);
            ps.setString(6, tool);
            ps.setString(7, toolClass);
            ps.setString(8, caller.spiffeId());
            ps.setString(9, caller.serial());
            ps.setTimestamp(10, caller.notAfter() == null ? null : Timestamp.from(Instant.parse(caller.notAfter())));
            ps.setString(11, Json.write(args));
            ps.setObject(12, approvalId);
            return ps.executeUpdate() == 1;
        }
    }

    public Optional<Stored> lockByKey(String key) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select invocation_id, args_digest, caller_spiffe_id, status, tool_name, tool_class, decision_allow, "
                        + "decision_id, policy_revision, reasons::text, result::text, requests "
                        + "from tool_invocation where idempotency_key = ? for update")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                List<String> reasons = new ArrayList<>();
                Json.parse(rs.getString(10)).forEach(n -> reasons.add(n.asString()));
                String result = rs.getString(11);
                return Optional.of(new Stored(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        new PolicyDecision(rs.getBoolean(7), rs.getString(8), rs.getString(9), reasons),
                        result == null ? null : Json.parse(result), rs.getInt(12)));
            }
        }
    }

    public void countReplay(UUID invocationId) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "update tool_invocation set requests = requests + 1, last_requested_at = now() where invocation_id = ?")) {
            ps.setObject(1, invocationId);
            ps.executeUpdate();
        }
    }

    public void complete(UUID invocationId, String status, PolicyDecision d, JsonNode result, int executions)
            throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "update tool_invocation set status = ?, decision_allow = ?, decision_id = ?, policy_revision = ?, "
                        + "reasons = ?::jsonb, result = ?::jsonb, executions = ? where invocation_id = ?")) {
            ps.setString(1, status);
            ps.setBoolean(2, d.allow());
            ps.setString(3, d.decisionId());
            ps.setString(4, d.policyRevision());
            ps.setString(5, Json.write(d.reasons()));
            ps.setString(6, result == null ? null : Json.write(result));
            ps.setInt(7, executions);
            ps.setObject(8, invocationId);
            ps.executeUpdate();
        }
    }

    public void insertSimulatedAction(UUID actionId, UUID invocationId, UUID runId, String actionType, String target,
            JsonNode parameters) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "insert into simulated_action (action_id, invocation_id, run_id, action_type, target, parameters) "
                        + "values (?, ?, ?, ?, ?, ?::jsonb)")) {
            ps.setObject(1, actionId);
            ps.setObject(2, invocationId);
            ps.setObject(3, runId);
            ps.setString(4, actionType);
            ps.setString(5, target);
            ps.setString(6, Json.write(parameters));
            ps.executeUpdate();
        }
    }

    public Optional<ApprovalGrant> approval(UUID approvalId) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select run_id, action_digest, approver, approved_at, expires_at from approval_grant where approval_id = ?")) {
            ps.setObject(1, approvalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ApprovalGrant(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()));
            }
        }
    }

    /** @return true if inserted, false if an identical grant existed; throws if a different grant uses the id. */
    public boolean registerApproval(UUID approvalId, ApprovalGrant g, String registeredBy) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "insert into approval_grant (approval_id, run_id, action_digest, approver, approved_at, expires_at, registered_by) "
                        + "values (?, ?, ?, ?, ?, ?, ?) on conflict (approval_id) do nothing")) {
            ps.setObject(1, approvalId);
            ps.setObject(2, g.runId());
            ps.setString(3, g.actionDigest());
            ps.setString(4, g.approver());
            ps.setTimestamp(5, Timestamp.from(g.approvedAt()));
            ps.setTimestamp(6, Timestamp.from(g.expiresAt()));
            ps.setString(7, registeredBy);
            if (ps.executeUpdate() == 1) {
                return true;
            }
        }
        ApprovalGrant existing = approval(approvalId).orElseThrow();
        if (!existing.runId().equals(g.runId()) || !existing.actionDigest().equals(g.actionDigest())) {
            throw new IllegalStateException("Approval id reused for a different action");
        }
        return false;
    }

    public ArrayNode audit(UUID runId, UUID invocationId) throws SQLException {
        String sql = "select i.invocation_id, i.run_id, i.task_id, i.tool_name, i.tool_class, i.status, i.requests, "
                + "i.executions, i.idempotency_key, i.caller_spiffe_id, i.caller_serial, i.caller_not_after, "
                + "i.decision_allow, i.decision_id, i.policy_revision, i.reasons::text, i.arguments::text, i.result::text, "
                + "i.created_at, i.last_requested_at, a.action_id, a.action_type, a.target, a.parameters::text, i.approval_id "
                + "from tool_invocation i left join simulated_action a on a.invocation_id = i.invocation_id "
                + "where i.status <> 'PENDING' and " + (invocationId != null ? "i.invocation_id = ?" : runId != null
                        ? "i.run_id = ?" : "true") + " order by i.created_at desc limit 500";
        ArrayNode items = Json.MAPPER.createArrayNode();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (invocationId != null) {
                ps.setObject(1, invocationId);
            } else if (runId != null) {
                ps.setObject(1, runId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode n = items.addObject();
                    n.put("invocationId", rs.getString(1));
                    n.put("runId", rs.getString(2));
                    n.put("taskId", rs.getString(3));
                    n.put("toolName", rs.getString(4));
                    n.put("toolClass", rs.getString(5));
                    n.put("status", rs.getString(6));
                    n.put("requests", rs.getInt(7));
                    n.put("executions", rs.getInt(8));
                    n.put("idempotencyKey", rs.getString(9));
                    n.put("simulated", true);
                    n.put("dataOrigin", "SYNTHETIC");
                    ObjectNode caller = n.putObject("caller");
                    caller.put("spiffeId", rs.getString(10));
                    caller.put("serial", rs.getString(11));
                    Timestamp na = rs.getTimestamp(12);
                    caller.put("notAfter", na == null ? null : na.toInstant().toString());
                    ObjectNode d = n.putObject("decision");
                    d.put("allow", rs.getBoolean(13));
                    d.put("decisionId", rs.getString(14));
                    d.put("policyRevision", rs.getString(15));
                    d.set("reasons", Json.parse(rs.getString(16)));
                    n.set("arguments", Json.parse(rs.getString(17)));
                    String result = rs.getString(18);
                    n.set("result", result == null ? null : Json.parse(result));
                    n.put("createdAt", rs.getTimestamp(19).toInstant().toString());
                    n.put("lastRequestedAt", rs.getTimestamp(20).toInstant().toString());
                    if (rs.getString(21) != null) {
                        ObjectNode se = n.putObject("sideEffect");
                        se.put("actionId", rs.getString(21));
                        se.put("actionType", rs.getString(22));
                        se.put("target", rs.getString(23));
                        se.set("parameters", Json.parse(rs.getString(24)));
                        se.put("simulated", true);
                    } else {
                        n.putNull("sideEffect");
                    }
                    n.put("approvalId", rs.getString(25));
                }
            }
        }
        return items;
    }

    public void reset() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "truncate simulated_action, approval_grant, tool_invocation")) {
            ps.executeUpdate();
        }
    }
}
