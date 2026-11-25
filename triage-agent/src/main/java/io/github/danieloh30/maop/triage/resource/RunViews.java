package io.github.danieloh30.maop.triage.resource;

import io.github.danieloh30.maop.triage.model.Run;
import io.github.danieloh30.maop.triage.model.Task;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.json.Json;
import java.util.List;

/** JSON views of the durable run record (contracts.md sections 2.2 and 5.2). */
final class RunViews {

    private RunViews() {
    }

    static ObjectNode summary(Run r) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("runId", r.runId.toString());
        n.put("state", r.state.name());
        n.put("outcome", r.outcome == null ? null : r.outcome.name());
        n.put("scenario", r.scenario.name());
        n.put("title", r.incident.title());
        n.put("service", r.incident.service());
        n.put("planSource", r.planSource.name());
        n.put("burstId", r.burstId == null ? null : r.burstId.toString());
        n.put("createdAt", r.createdAt.toString());
        n.put("updatedAt", r.updatedAt == null ? null : r.updatedAt.toString());
        n.put("traceId", r.traceId);
        // Labels let a client tell runs apart by origin, e.g. {"source": "demo-verify"} from ./demo.sh verify.
        n.set("labels", r.labels == null ? Json.MAPPER.createObjectNode() : r.labels);
        return n;
    }

    static ObjectNode full(Run r, List<Task> tasks) {
        ObjectNode n = summary(r);
        n.put("version", r.version);
        n.put("outcomeDetail", r.outcomeDetail);
        n.put("remediationMode", r.remediationMode.name());
        n.put("faultInjection", r.faultInjection.name());
        n.put("analysisMode", r.analysisMode.name());
        ObjectNode inc = n.putObject("incident");
        inc.put("title", r.incident.title());
        inc.put("description", r.incident.description());
        inc.put("service", r.incident.service());
        n.set("classification", r.classification);
        n.put("deadline", r.deadline.toString());
        n.put("approvalDeadline", r.approvalDeadline == null ? null : r.approvalDeadline.toString());
        n.set("budget", Json.tree(r.budget));
        n.set("usage", Json.tree(r.usage));
        n.putObject("model").put("mode", r.modelMode).put("name", r.modelName);
        ArrayNode ts = n.putArray("tasks");
        ArrayNode ev = n.putArray("evidence");
        for (Task t : tasks) {
            ObjectNode o = ts.addObject();
            o.put("taskId", t.taskId.toString());
            o.put("kind", t.kind.name());
            o.put("topic", t.topic == null ? null : t.topic.name());
            o.put("state", t.state.name());
            o.put("deliveries", t.deliveries);
            o.put("failureReason", t.failureReason);
            o.put("detail", t.detail);
            o.put("startedAt", t.createdAt == null ? null : t.createdAt.toString());
            o.put("finishedAt", t.finishedAt == null ? null : t.finishedAt.toString());
            o.set("usage", t.usage);
            if (t.evidence != null) {
                ev.add(t.evidence);
            }
        }
        n.put("evidenceCompleteness", r.evidenceCompleteness);
        n.set("recommendation", r.recommendation);
        n.set("executionAttempt", r.executionAttempt);
        n.set("approval", r.approval);
        n.set("labels", r.labels);
        return n;
    }
}
