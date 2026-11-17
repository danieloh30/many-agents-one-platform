package io.github.danieloh30.maop.evidence.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.model.EvidenceItem;
import io.github.danieloh30.maop.common.model.EvidenceTaskCommand;
import io.github.danieloh30.maop.common.model.Fact;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.common.model.Topic;
import io.github.danieloh30.maop.common.agentic.AgentErrors;
import io.github.danieloh30.maop.common.agentic.AgentInvocation;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tools.ToolCatalog;
import io.github.danieloh30.maop.common.tools.ToolClient;
import io.github.danieloh30.maop.common.tools.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gathers evidence for one task: deterministic, policy-checked tool calls for the topic, then the declarative
 * {@link EvidenceAnalystAgent} turns the results into a finding whose facts must cite invocations actually returned
 * to this task (one bounded repair attempt).
 */
@ApplicationScoped
public class EvidenceAgent {

    public static final String PURPOSE = "evidence.summarize";



    @Inject
    EvidenceAnalystAgent analyst;
    @Inject
    ToolClient tools;

    public record Gathered(EvidenceItem item, BudgetTracker budget) {
    }

    /** Baseline tool calls; returns invocation id → summary JSON. */
    public Map<String, ObjectNode> baseline(EvidenceTaskCommand cmd, BudgetTracker budget) {
        Map<String, ObjectNode> results = new LinkedHashMap<>();
        String svc = cmd.incident().service();
        switch (cmd.topic()) {
            case DEPLOYMENT_HISTORY -> call(cmd, budget, results, "deployment.get_history", Map.of("service", svc, "limit", 5));
            case ERROR_SUMMARY -> call(cmd, budget, results, "telemetry.get_error_summary", Map.of("service", svc, "windowMinutes", 30));
            case SERVICE_CATALOG -> {
                call(cmd, budget, results, "catalog.get_service", Map.of("service", svc));
                call(cmd, budget, results, "catalog.get_dependencies", Map.of("service", svc, "depth", 1));
            }
            case CONFIG_DIFF -> {
                ToolResult history = call(cmd, budget, results, "deployment.get_history", Map.of("service", svc, "limit", 2));
                JsonNode deps = history == null ? null : history.result().path("deployments");
                if (deps != null && deps.size() >= 2) {
                    call(cmd, budget, results, "deployment.get_diff", Map.of("service", svc,
                            "fromVersion", deps.get(1).path("version").asText(), "toVersion", deps.get(0).path("version").asText()));
                }
            }
        }
        if (results.values().stream().allMatch(n -> "DENIED".equals(n.path("status").asText()))) {
            throw new TaskFailure(FailureReason.TOOL_DENIED, false, "All baseline tool calls were denied by policy");
        }
        return results;
    }

    private ToolResult call(EvidenceTaskCommand cmd, BudgetTracker budget, Map<String, ObjectNode> results, String tool,
            Map<String, Object> args) {
        ToolResult r = tools.invoke(cmd.runId(), cmd.taskId(), tool, args, null, cmd.deadline(), budget);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("invocationId", r.invocationId());
        n.put("toolName", tool);
        n.put("status", r.status());
        n.set("arguments", Json.tree(args));
        n.set("result", r.result());
        results.put(r.invocationId(), n);
        return r.denied() ? null : r;
    }

    public Gathered gather(EvidenceTaskCommand cmd, Map<String, ObjectNode> baseline, BudgetTracker budget) {
        Map<String, String> invocations = new ConcurrentHashMap<>();
        baseline.values().forEach(n -> invocations.put(n.get("invocationId").asText(), n.get("toolName").asText()));
        String toolResults = Json.write(baseline.values());
        String feedback = "";
        TaskFailure last = new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false, "Evidence output invalid");
        for (int attempt = 0; attempt < 2 && budget.usage().modelCalls() < cmd.budget().maxModelCalls(); attempt++) {
            String fb = feedback;
            EvidenceFinding finding;
            try {
                finding = AgentInvocation.call(analyst, cmd.deadline(), budget, id -> analyst.analyse(id, cmd.topic().name(),
                        cmd.incident().title(), cmd.incident().service(), cmd.question(), toolResults, fb));
            } catch (RuntimeException e) {
                TaskFailure tf = AgentErrors.taskFailure(e);
                if (tf != null) {
                    throw tf;
                }
                last = new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false,
                        "Evidence output could not be parsed (" + e.getClass().getSimpleName() + ")");
                feedback = AgentErrors.repairFeedback(last.getMessage());
                continue;
            }
            try {
                return new Gathered(parse(cmd, Json.write(finding), invocations), budget);
            } catch (TaskFailure invalid) {
                if (invalid.reason() != FailureReason.MODEL_OUTPUT_INVALID) {
                    throw invalid;
                }
                last = invalid;
                feedback = AgentErrors.repairFeedback(invalid.getMessage());
            }
        }
        throw last;
    }

    public static EvidenceItem parse(EvidenceTaskCommand cmd, String text, Map<String, String> invocations) {
        String json = Json.extractObject(text);
        if (json == null) {
            throw new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false, "Evidence output contained no JSON object");
        }
        JsonNode n;
        try {
            n = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false, "Evidence output is not valid JSON");
        }
        String finding = n.path("finding").asText("");
        double confidence = n.path("confidence").asDouble(-1);
        if (finding.isBlank() || finding.length() > 400 || confidence < 0 || confidence > 1) {
            throw new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false, "Evidence finding/confidence invalid");
        }
        List<Fact> facts = new ArrayList<>();
        Set<String> known = invocations.keySet();
        for (JsonNode f : n.path("facts")) {
            String id = f.path("invocationId").asText();
            if (!known.contains(id)) {
                throw new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false,
                        "Fact cites invocation " + id + " that was not returned to this task");
            }
            String statement = f.path("statement").asText("");
            if (statement.isBlank()) {
                continue;
            }
            facts.add(new Fact(statement.length() > 300 ? statement.substring(0, 300) : statement, id, invocations.get(id)));
        }
        if (facts.isEmpty() || facts.size() > 6) {
            throw new TaskFailure(FailureReason.MODEL_OUTPUT_INVALID, false, "Evidence needs 1-6 facts citing tool invocations");
        }
        String tid = cmd.taskId().toString();
        return new EvidenceItem("E-" + tid.substring(tid.length() - 8), cmd.taskId(), cmd.topic(), finding, confidence, facts,
                "SYNTHETIC", null);
    }

    public static Topic topicOf(String systemText) {
        int i = systemText.indexOf("[topic:");
        return i < 0 ? null : Topic.valueOf(systemText.substring(i + 7, systemText.indexOf(']', i)));
    }
}
