package io.github.danieloh30.maop.triage.service;

import io.github.danieloh30.maop.triage.model.Run;
import io.github.danieloh30.maop.triage.model.Task;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.github.danieloh30.maop.common.model.AnalysisMode;
import io.github.danieloh30.maop.common.model.Budget;
import io.github.danieloh30.maop.common.model.FaultInjection;
import io.github.danieloh30.maop.common.model.Incident;
import io.github.danieloh30.maop.common.model.Outcome;
import io.github.danieloh30.maop.common.model.PlanSource;
import io.github.danieloh30.maop.common.model.RemediationMode;
import io.github.danieloh30.maop.common.model.RunState;
import io.github.danieloh30.maop.common.model.Scenario;
import io.github.danieloh30.maop.common.model.TaskKind;
import io.github.danieloh30.maop.common.model.TaskState;
import io.github.danieloh30.maop.common.model.Topic;
import io.github.danieloh30.maop.common.model.Usage;
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

/** JDBC persistence for the triage schema. Every method joins the caller's JTA transaction. */
@ApplicationScoped
public class RunStore {

    public static final class ConcurrentUpdateException extends RuntimeException {
        public ConcurrentUpdateException(UUID runId) {
            super("Run " + runId + " was modified concurrently", null, false, false);
        }
    }

    private static final String RUN_COLS = "run_id, version, state, outcome, outcome_detail, scenario, remediation_mode, "
            + "fault_injection, plan_source, burst_id, title, description, service, classification::text, budget::text, "
            + "usage::text, model_mode, model_name, trace_id, traceparent, recommendation::text, execution_attempt::text, "
            + "evidence_completeness, approval::text, labels::text, created_at, updated_at, deadline, approval_deadline, analysis_mode";

    @Inject
    AgroalDataSource ds;

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String json(Object o) {
        return o == null ? null : Json.write(o);
    }

    private static JsonNode node(String s) {
        return s == null ? null : Json.parse(s);
    }

    private static <E extends Enum<E>> E en(Class<E> t, String v) {
        return v == null ? null : Enum.valueOf(t, v);
    }

    public void insert(Run r) {
        String sql = "insert into run (run_id, version, state, scenario, remediation_mode, fault_injection, plan_source, "
                + "burst_id, title, description, service, budget, usage, model_mode, model_name, trace_id, traceparent, "
                + "labels, created_at, updated_at, deadline, analysis_mode) values (?,1,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?::jsonb,?,?,?,?)";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, r.runId);
            ps.setString(i++, r.state.name());
            ps.setString(i++, r.scenario.name());
            ps.setString(i++, r.remediationMode.name());
            ps.setString(i++, r.faultInjection.name());
            ps.setString(i++, r.planSource.name());
            ps.setObject(i++, r.burstId);
            ps.setString(i++, r.incident.title());
            ps.setString(i++, r.incident.description());
            ps.setString(i++, r.incident.service());
            ps.setString(i++, json(r.budget));
            ps.setString(i++, json(r.usage));
            ps.setString(i++, r.modelMode);
            ps.setString(i++, r.modelName);
            ps.setString(i++, r.traceId);
            ps.setString(i++, r.traceparent);
            ps.setString(i++, json(r.labels == null ? Json.MAPPER.createObjectNode() : r.labels));
            ps.setTimestamp(i++, ts(r.createdAt));
            ps.setTimestamp(i++, ts(r.createdAt));
            ps.setTimestamp(i++, ts(r.deadline));
            ps.setString(i, r.analysisMode.name());
            ps.executeUpdate();
            r.version = 1;
            r.updatedAt = r.createdAt;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Run> find(UUID runId) {
        return one("select " + RUN_COLS + " from run where run_id = ?", runId);
    }

    /** Pessimistic row lock: serialises concurrent handlers of the same run. */
    public Optional<Run> lock(UUID runId) {
        return one("select " + RUN_COLS + " from run where run_id = ? for update", runId);
    }

    private Optional<Run> one(String sql, UUID runId) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Run map(ResultSet rs) throws SQLException {
        Run r = new Run();
        r.runId = rs.getObject(1, UUID.class);
        r.version = rs.getInt(2);
        r.state = RunState.valueOf(rs.getString(3));
        r.outcome = en(Outcome.class, rs.getString(4));
        r.outcomeDetail = rs.getString(5);
        r.scenario = Scenario.valueOf(rs.getString(6));
        r.remediationMode = RemediationMode.valueOf(rs.getString(7));
        r.faultInjection = FaultInjection.valueOf(rs.getString(8));
        r.planSource = PlanSource.valueOf(rs.getString(9));
        r.burstId = rs.getObject(10, UUID.class);
        r.incident = new Incident(rs.getString(11), rs.getString(12), rs.getString(13));
        r.classification = node(rs.getString(14));
        r.budget = Json.read(rs.getString(15), Budget.class);
        r.usage = Json.read(rs.getString(16), Usage.class);
        r.modelMode = rs.getString(17);
        r.modelName = rs.getString(18);
        r.traceId = rs.getString(19);
        r.traceparent = rs.getString(20);
        r.recommendation = node(rs.getString(21));
        r.executionAttempt = node(rs.getString(22));
        r.evidenceCompleteness = rs.getString(23);
        r.approval = node(rs.getString(24));
        r.labels = node(rs.getString(25));
        r.createdAt = inst(rs.getTimestamp(26));
        r.updatedAt = inst(rs.getTimestamp(27));
        r.deadline = inst(rs.getTimestamp(28));
        r.approvalDeadline = inst(rs.getTimestamp(29));
        r.analysisMode = AnalysisMode.valueOf(rs.getString(30));
        return r;
    }

    /** Optimistic update: fails with {@link ConcurrentUpdateException} if the version moved. */
    public void update(Run r) {
        String sql = "update run set version = version + 1, state = ?, outcome = ?, outcome_detail = ?, classification = ?::jsonb, "
                + "usage = ?::jsonb, recommendation = ?::jsonb, execution_attempt = ?::jsonb, evidence_completeness = ?, "
                + "approval = ?::jsonb, deadline = ?, approval_deadline = ?, updated_at = now() where run_id = ? and version = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, r.state.name());
            ps.setString(i++, r.outcome == null ? null : r.outcome.name());
            ps.setString(i++, r.outcomeDetail);
            ps.setString(i++, json(r.classification));
            ps.setString(i++, json(r.usage));
            ps.setString(i++, json(r.recommendation));
            ps.setString(i++, json(r.executionAttempt));
            ps.setString(i++, r.evidenceCompleteness);
            ps.setString(i++, json(r.approval));
            ps.setTimestamp(i++, ts(r.deadline));
            ps.setTimestamp(i++, ts(r.approvalDeadline));
            ps.setObject(i++, r.runId);
            ps.setInt(i, r.version);
            if (ps.executeUpdate() != 1) {
                throw new ConcurrentUpdateException(r.runId);
            }
            r.version++;
            r.updatedAt = Instant.now();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- tasks ---------------------------------------------------------------------------

    public void insertTask(Task t) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "insert into run_task (task_id, run_id, kind, topic, state) values (?, ?, ?, ?, ?)")) {
            ps.setObject(1, t.taskId);
            ps.setObject(2, t.runId);
            ps.setString(3, t.kind.name());
            ps.setString(4, t.topic == null ? null : t.topic.name());
            ps.setString(5, t.state.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateTask(Task t) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "update run_task set state = ?, deliveries = ?, failure_reason = ?, detail = ?, evidence = ?::jsonb, "
                        + "usage = ?::jsonb, finished_at = ? where task_id = ?")) {
            ps.setString(1, t.state.name());
            ps.setInt(2, t.deliveries);
            ps.setString(3, t.failureReason);
            ps.setString(4, t.detail);
            ps.setString(5, json(t.evidence));
            ps.setString(6, json(t.usage));
            ps.setTimestamp(7, ts(t.finishedAt));
            ps.setObject(8, t.taskId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Task> tasks(UUID runId) {
        List<Task> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select task_id, run_id, kind, topic, state, deliveries, failure_reason, detail, evidence::text, usage::text, "
                        + "created_at, finished_at from run_task where run_id = ? order by created_at, task_id")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Task t = new Task();
                    t.taskId = rs.getObject(1, UUID.class);
                    t.runId = rs.getObject(2, UUID.class);
                    t.kind = TaskKind.valueOf(rs.getString(3));
                    t.topic = en(Topic.class, rs.getString(4));
                    t.state = TaskState.valueOf(rs.getString(5));
                    t.deliveries = rs.getInt(6);
                    t.failureReason = rs.getString(7);
                    t.detail = rs.getString(8);
                    t.evidence = node(rs.getString(9));
                    t.usage = node(rs.getString(10));
                    t.createdAt = inst(rs.getTimestamp(11));
                    t.finishedAt = inst(rs.getTimestamp(12));
                    out.add(t);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // --- timeline ------------------------------------------------------------------------

    public void timeline(UUID runId, String kind, RunState from, RunState to, UUID taskId, String message, Object data) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "insert into run_timeline (run_id, kind, from_state, to_state, task_id, message, data) values (?, ?, ?, ?, ?, ?, ?::jsonb)")) {
            ps.setObject(1, runId);
            ps.setString(2, kind);
            ps.setString(3, from == null ? null : from.name());
            ps.setString(4, to == null ? null : to.name());
            ps.setObject(5, taskId);
            ps.setString(6, message);
            ps.setString(7, json(data));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public ArrayNode timeline(UUID runId) {
        ArrayNode items = Json.MAPPER.createArrayNode();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select seq, at, kind, from_state, to_state, task_id, message, data::text from run_timeline where run_id = ? order by seq")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode n = items.addObject();
                    n.put("seq", rs.getLong(1));
                    n.put("at", rs.getTimestamp(2).toInstant().toString());
                    n.put("kind", rs.getString(3));
                    n.put("from", rs.getString(4));
                    n.put("to", rs.getString(5));
                    n.put("taskId", rs.getString(6));
                    n.put("message", rs.getString(7));
                    n.set("data", node(rs.getString(8)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return items;
    }

    // --- idempotency, listing, limits ----------------------------------------------------

    /** @return the existing (runId, requestHash) for the key, or empty after recording it for {@code runId}. */
    public Optional<String[]> claimIdempotencyKey(String key, String requestHash, UUID runId) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into idempotency_key (idem_key, request_hash, run_id) values (?, ?, ?) on conflict do nothing")) {
                ps.setString(1, key);
                ps.setString(2, requestHash);
                ps.setObject(3, runId);
                if (ps.executeUpdate() == 1) {
                    return Optional.empty();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "select run_id::text, request_hash from idempotency_key where idem_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return Optional.of(new String[] { rs.getString(1), rs.getString(2) });
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Run> list(int limit, Instant before) {
        List<Run> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select " + RUN_COLS + " from run where (?::timestamptz is null or created_at < ?) order by created_at desc limit ?")) {
            ps.setTimestamp(1, ts(before));
            ps.setTimestamp(2, ts(before));
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public long scalar(String sql, Object... params) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i] instanceof Instant in ? ts(in) : params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long activeRuns() {
        return scalar("select count(*) from run where state not in ('COMPLETED','NEEDS_REVIEW','TIMED_OUT','FAILED','CANCELLED')");
    }

    public long createdSince(Instant since) {
        return scalar("select count(*) from run where created_at >= ?", since);
    }

    /** Overdue run ids, locked with SKIP LOCKED so concurrent sweepers never contend. */
    public List<UUID> overdue(int limit) {
        List<UUID> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select run_id from run where (state not in ('COMPLETED','NEEDS_REVIEW','TIMED_OUT','FAILED','CANCELLED','AWAITING_APPROVAL') "
                        + "and deadline < now()) or (state = 'AWAITING_APPROVAL' and approval_deadline < now()) "
                        + "order by deadline limit ? for update skip locked")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // --- bursts --------------------------------------------------------------------------

    public void insertBurst(UUID burstId, int runs) {
        exec("insert into burst (burst_id, active, runs) values (?, true, ?)", burstId, runs);
    }

    public Optional<ObjectNode> activeBurst() {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select b.burst_id, b.runs, b.started_at, (select count(*) from run r where r.burst_id = b.burst_id "
                        + "and r.state in ('COMPLETED','NEEDS_REVIEW','TIMED_OUT','FAILED','CANCELLED')) "
                        + "from burst b where b.active order by b.started_at desc limit 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ObjectNode n = Json.MAPPER.createObjectNode();
                n.put("active", true);
                n.put("burstId", rs.getString(1));
                n.put("runs", rs.getInt(2));
                n.put("startedAt", rs.getTimestamp(3).toInstant().toString());
                n.put("completed", rs.getInt(4));
                return Optional.of(n);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean activeBurstPresent() {
        return scalar("select count(*) from burst where active") > 0;
    }

    public void endBurst(UUID burstId) {
        exec("update burst set active = false, stopped_at = now() where burst_id = ? and active", burstId);
    }

    public List<UUID> activeRunsOfBurst(UUID burstId) {
        List<UUID> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select run_id from run where burst_id = ? and state not in ('COMPLETED','NEEDS_REVIEW','TIMED_OUT','FAILED','CANCELLED')")) {
            ps.setObject(1, burstId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void reset() {
        exec("truncate run_timeline, run_task, run, idempotency_key, burst, inbox_event, outbox_event");
    }

    private void exec(String sql, Object... params) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
