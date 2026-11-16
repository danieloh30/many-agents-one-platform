package io.github.danieloh30.maop.evidence.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.model.EvidenceItem;
import io.github.danieloh30.maop.common.model.EvidenceTaskCommand;
import io.github.danieloh30.maop.common.model.Fact;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.common.model.Topic;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.agentic.ModelGateway;
import io.github.danieloh30.maop.common.agentic.Purposes;
import io.github.danieloh30.maop.common.agentic.ToolLoop;
import io.github.danieloh30.maop.common.tools.ToolCatalog;
import io.github.danieloh30.maop.common.tools.ToolClient;
import io.github.danieloh30.maop.common.tools.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gathers evidence for one task: deterministic baseline tool calls for the topic (so evidence never depends on the
 * model choosing to call a tool), an optional bounded follow-up the model may request, and a structured finding
 * whose facts must cite invocations actually returned to this task.
 */
@ApplicationScoped
public class EvidenceAgent {

    public static final String PURPOSE = "evidence.summarize";

    static final String SYSTEM = Purposes.marker(PURPOSE) + " [topic:%s]" + """

            You are an evidence worker in an incident-response platform. Use ONLY the tool results provided (and any
            additional read tool you call) to answer the question. All data is synthetic demonstration data.
            Respond with ONLY a JSON object:
            {"finding": "one or two factual sentences (max 300 chars)",
             "confidence": number 0..1,
             "facts": [{"statement": "a specific fact (max 200 chars)", "invocationId": "<id of the tool invocation it came from>",
                        "toolName": "<tool name>"}]}
            Include 1 to 5 facts. Every invocationId MUST be one listed in the tool results. Do not include reasoning steps.""";

    @Inject
    ModelGateway model;
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
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM.formatted(cmd.topic().name())));
        messages.add(UserMessage.from("Incident: " + cmd.incident().title() + " (service " + cmd.incident().service() + ")\n"
                + "Question: " + cmd.question() + "\nTool results:\n" + Json.write(baseline.values())));
        String text = ToolLoop.run(model, PURPOSE, messages, ToolLoop.specifications(ToolCatalog.readTools()),
                req -> followUp(cmd, budget, req, invocations), budget, cmd.deadline(), cmd.budget().maxOutputTokens());
        EvidenceItem item;
        try {
            item = parse(cmd, text, invocations);
        } catch (TaskFailure invalid) {
            if (invalid.reason() != FailureReason.MODEL_OUTPUT_INVALID
                    || budget.usage().modelCalls() >= cmd.budget().maxModelCalls()) {
                throw invalid;
            }
            // One repair attempt within the task's model-call budget.
            messages.add(dev.langchain4j.data.message.AiMessage.from(text == null ? "" : text));
            messages.add(UserMessage.from("Your answer was invalid: " + invalid.getMessage()
                    + ". Reply with ONLY the corrected JSON object."));
            String repaired = model.call(PURPOSE, dev.langchain4j.model.chat.request.ChatRequest.builder().messages(messages)
                    .maxOutputTokens(cmd.budget().maxOutputTokens()).build(), cmd.deadline(), budget).response().aiMessage().text();
            item = parse(cmd, repaired, invocations);
        }
        return new Gathered(item, budget);
    }

    private String followUp(EvidenceTaskCommand cmd, BudgetTracker budget, ToolExecutionRequest req,
            Map<String, String> invocations) {
        String tool = ToolLoop.toolName(req.name());
        if (ToolCatalog.find(tool).filter(t -> t.toolClass() == ToolCatalog.ToolClass.READ).isEmpty()) {
            return "{\"error\":\"unknown or non-read tool\"}";
        }
        Map<String, Object> args;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = Json.read(req.arguments() == null || req.arguments().isBlank() ? "{}" : req.arguments(), Map.class);
            args = a;
        } catch (IllegalArgumentException e) {
            return "{\"error\":\"arguments must be a JSON object\"}";
        }
        try {
            ToolResult r = tools.invoke(cmd.runId(), cmd.taskId(), tool, args, null, cmd.deadline(), budget);
            invocations.put(r.invocationId(), tool);
            ObjectNode n = Json.MAPPER.createObjectNode();
            n.put("invocationId", r.invocationId());
            n.put("status", r.status());
            n.set("result", r.result());
            return Json.write(n);
        } catch (TaskFailure f) {
            if (f.reason() == FailureReason.INVALID_COMMAND) {
                return "{\"error\":" + Json.write(f.getMessage()) + "}";
            }
            throw f;
        }
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
