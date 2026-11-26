package io.github.danieloh30.maop.triage;

import io.github.danieloh30.maop.triage.model.Run;
import io.github.danieloh30.maop.triage.service.ResultFolder;
import io.github.danieloh30.maop.triage.service.RunStore;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.model.ActionCompleted;
import io.github.danieloh30.maop.common.model.ActionDigests;
import io.github.danieloh30.maop.common.model.ActionType;
import io.github.danieloh30.maop.common.model.ActionCommand;
import io.github.danieloh30.maop.common.model.EvidenceCompleted;
import io.github.danieloh30.maop.common.model.EvidenceItem;
import io.github.danieloh30.maop.common.model.EvidenceTaskCommand;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.common.model.Fact;
import io.github.danieloh30.maop.common.model.ProposedAction;
import io.github.danieloh30.maop.common.model.Recommendation;
import io.github.danieloh30.maop.common.model.RecommendationCommand;
import io.github.danieloh30.maop.common.model.RecommendationCompleted;
import io.github.danieloh30.maop.common.model.TaskFailed;
import io.github.danieloh30.maop.common.model.TaskKind;
import io.github.danieloh30.maop.common.model.Usage;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.messaging.JetStreamClient;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.github.danieloh30.maop.outbox.OutboxRelay;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = TriageResources.class, restrictToAnnotatedClass = true)
class TriageWorkflowTest {

    @Inject
    JetStreamClient js;
    @Inject
    ResultFolder folder;
    @Inject
    RunStore store;
    @Inject
    OutboxRelay relay;

    static String body(String scenario, String remediation, String fault) {
        return Json.write(Map.of("title", "Checkout error rate elevated after release",
                "description", "5xx on POST /checkout rose from 0.2% to 7%.", "service", "checkout",
                "scenario", scenario, "remediationMode", remediation, "faultInjection", fault));
    }

    static UUID submit(String body) {
        String id = given().contentType(ContentType.JSON).header("Idempotency-Key", "k-" + Ids.v7()).body(body)
                .post("/v1/runs").then().statusCode(202).extract().path("runId");
        return UUID.fromString(id);
    }

    static JsonPath run(UUID id) {
        return given().get("/v1/runs/" + id).then().statusCode(200).extract().jsonPath();
    }

    static void awaitState(UUID id, String state) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> state.equals(run(id).getString("state")));
    }

    void publish(String type, CloudEvent command, Object data) throws Exception {
        js.publish(EventTypes.eventSubject(type), CloudEvent.builder("test-worker", type)
                .id(Ids.v5(command.taskId(), type)).task(command.runId(), command.taskId()).data(data).build());
    }

    static EvidenceItem evidence(EvidenceTaskCommand c) {
        return new EvidenceItem("E-" + c.taskId().toString().substring(28), c.taskId(), c.topic(), "finding " + c.topic(), 0.9,
                List.of(new Fact("fact", "inv-" + c.taskId(), "deployment.get_history")), "SYNTHETIC", null);
    }

    List<CloudEvent> evidenceCommands(UUID runId, int n) throws Exception {
        return FakeWorker.pull(js, Subjects.WORK_STREAM, Subjects.CONSUMER_EVIDENCE, runId, n, Duration.ofSeconds(20));
    }

    @Test
    void successfulRunWithApprovalCompletesInOneTrace() throws Exception {
        UUID id = submit(body("SUCCESS", "PROPOSE_ONLY", "NONE"));
        String traceId = run(id).getString("traceId");
        assertTrue(traceId != null && traceId.matches("[0-9a-f]{32}"), "every submitted run must expose a valid trace ID");
        List<CloudEvent> cmds = evidenceCommands(id, 3);
        assertEquals(3, cmds.size(), "deterministic triage plan delegates three evidence tasks");
        for (CloudEvent c : cmds) {
            assertEquals(traceId, Tracing.traceId(Tracing.extract(c)), "command continues the run's trace");
            EvidenceTaskCommand cmd = c.dataAs(EvidenceTaskCommand.class);
            publish(EventTypes.EVIDENCE_COMPLETED, c, new EvidenceCompleted(cmd.taskId(), evidence(cmd),
                    new Usage(2, 1, 100, 50, 0), 1));
        }
        awaitState(id, "RECOMMENDING");
        CloudEvent recCmd = FakeWorker.pull(js, Subjects.WORK_STREAM, Subjects.CONSUMER_RECOMMENDATION, id, 1,
                Duration.ofSeconds(20)).getFirst();
        RecommendationCommand rc = recCmd.dataAs(RecommendationCommand.class);
        assertEquals(3, rc.evidence().size());
        ProposedAction action = ActionDigests.withDigest(id, new ProposedAction(ActionType.ROLLBACK_DEPLOYMENT, "checkout",
                Map.of("toVersion", "2.13.2"), true, null));
        publish(EventTypes.RECOMMENDATION_COMPLETED, recCmd, new RecommendationCompleted(rc.taskId(),
                new Recommendation("Roll back", "because", action, List.of(rc.evidence().getFirst().evidenceId()), 0.8, List.of()),
                null, new Usage(1, 0, 10, 10, 0), 1));
        awaitState(id, "AWAITING_APPROVAL");

        // Wrong digest is refused; the right one starts the simulated action.
        given().contentType(ContentType.JSON).body(Map.of("decision", "APPROVE", "actionDigest", "sha256:wrong"))
                .post("/v1/runs/" + id + "/approval").then().statusCode(409);
        given().contentType(ContentType.JSON).body(Map.of("decision", "APPROVE", "actionDigest", action.actionDigest()))
                .post("/v1/runs/" + id + "/approval").then().statusCode(200);
        assertEquals("EXECUTING_ACTION", run(id).getString("state"));

        // The grant is delivered (after two transient 503s) strictly before the action command.
        CloudEvent actCmd = FakeWorker.pull(js, Subjects.WORK_STREAM, Subjects.CONSUMER_RECOMMENDATION, id, 1,
                Duration.ofSeconds(20)).getFirst();
        assertTrue(TriageResources.toolService.requests.stream().filter(r -> r.path().startsWith("/v1/approvals/")
                && r.method().equals("PUT")).count() >= 3, "grant retried until accepted");
        ActionCommand ac = actCmd.dataAs(ActionCommand.class);
        assertEquals(run(id).getString("approval.approvalId"), ac.approvalId().toString());
        publish(EventTypes.ACTION_COMPLETED, actCmd, new ActionCompleted(ac.taskId(), "inv-x", "EXECUTED", false, Usage.ZERO, 1));
        awaitState(id, "COMPLETED");
        JsonPath done = run(id);
        assertEquals("REMEDIATION_SIMULATED", done.getString("outcome"));
        assertEquals(3, done.getInt("usage.toolCalls"));
        List<String> states = given().get("/v1/runs/" + id + "/timeline").then().extract().jsonPath()
                .getList("items.findAll { it.kind == 'STATE_CHANGED' }.to");
        assertEquals(List.of("SUBMITTED", "TRIAGING", "GATHERING_EVIDENCE", "RECOMMENDING", "AWAITING_APPROVAL",
                "EXECUTING_ACTION", "COMPLETED"), states);
    }

    @Test
    void submissionRetainsTheCallingConsolesTrace() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String id = given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "trace-" + Ids.v7())
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .body(body("SUCCESS", "PROPOSE_ONLY", "NONE")).post("/v1/runs")
                .then().statusCode(202).extract().path("runId");
        assertEquals(traceId, run(UUID.fromString(id)).getString("traceId"));
    }

    @Test
    void sandboxedAnalysisAddsOneCodePlannedTaskOnlyWhenRequested() throws Exception {
        java.util.Map<String, Object> req = new java.util.LinkedHashMap<>(Json.MAPPER.readValue(
                body("SANDBOX", "PROPOSE_ONLY", "NONE"), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                }));
        req.put("analysisMode", "SANDBOXED_SCRIPT");
        UUID id = submit(Json.write(req));
        assertEquals("SANDBOXED_SCRIPT", run(id).getString("analysisMode"));
        List<CloudEvent> cmds = evidenceCommands(id, 4);
        List<String> topics = cmds.stream().map(c -> c.dataAs(EvidenceTaskCommand.class).topic().name()).toList();
        assertEquals(4, topics.size(), "three planned tasks plus the sandboxed analysis");
        assertEquals("LOG_ANALYSIS", topics.getLast(), "the code-planned task is dispatched last");
        assertEquals(1, topics.stream().filter("LOG_ANALYSIS"::equals).count());

        UUID plain = submit(body("SUCCESS", "PROPOSE_ONLY", "NONE"));
        assertEquals("NONE", run(plain).getString("analysisMode"), "the setting defaults to NONE");
        List<String> plainTopics = evidenceCommands(plain, 3).stream()
                .map(c -> c.dataAs(EvidenceTaskCommand.class).topic().name()).toList();
        assertEquals(List.of("DEPLOYMENT_HISTORY", "CONFIG_DIFF", "ERROR_SUMMARY"), plainTopics);
    }

    @Test
    void duplicateSubmissionReturnsTheOriginalRun() {
        String key = "dup-" + Ids.v7();
        String b = body("SUCCESS", "PROPOSE_ONLY", "NONE");
        String first = given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(b).post("/v1/runs")
                .then().statusCode(202).extract().path("runId");
        String second = given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(b).post("/v1/runs")
                .then().statusCode(200).extract().path("runId");
        assertEquals(first, second);
        given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(body("POLICY_DENIAL", "ATTEMPT_UNAPPROVED", "NONE"))
                .post("/v1/runs").then().statusCode(409).body("code", org.hamcrest.Matchers.equalTo("IDEMPOTENCY_KEY_REUSED"));
        given().contentType(ContentType.JSON).body(b).post("/v1/runs").then().statusCode(400);
    }

    @Test
    void redeliveredAndConcurrentResultsAdvanceTheRunExactlyOnce() throws Exception {
        UUID id = submit(body("SUCCESS", "PROPOSE_ONLY", "NONE"));
        List<CloudEvent> cmds = evidenceCommands(id, 3);
        List<CloudEvent> results = new ArrayList<>();
        for (CloudEvent c : cmds) {
            EvidenceTaskCommand cmd = c.dataAs(EvidenceTaskCommand.class);
            results.add(CloudEvent.builder("test-worker", EventTypes.EVIDENCE_COMPLETED).id(Ids.v5(cmd.taskId(), "ev"))
                    .task(id, cmd.taskId()).data(new EvidenceCompleted(cmd.taskId(), evidence(cmd), Usage.ZERO, 1)).build());
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) { // every result twice, all at once
            for (CloudEvent r : results) {
                fs.add(pool.submit(() -> {
                    for (int attempt = 0; ; attempt++) {
                        try {
                            return QuarkusTransaction.requiringNew().call(() -> folder.fold(r));
                        } catch (RuntimeException e) {
                            if (attempt > 5) {
                                throw e;
                            }
                        }
                    }
                }));
            }
        }
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        awaitState(id, "RECOMMENDING");
        long recommendationTasks = QuarkusTransaction.requiringNew().call(() ->
                store.tasks(id).stream().filter(t -> t.kind == TaskKind.RECOMMENDATION).count());
        assertEquals(1, recommendationTasks, "exactly one recommendation dispatched");
        assertEquals(1, FakeWorker.pull(js, Subjects.WORK_STREAM, Subjects.CONSUMER_RECOMMENDATION, id, 2,
                Duration.ofSeconds(3)).size());
    }

    @Test
    void tokenBudgetExhaustionMovesTheRunToReview() throws Exception {
        UUID id = submit(body("SUCCESS", "PROPOSE_ONLY", "NONE"));
        CloudEvent c = evidenceCommands(id, 3).getFirst();
        EvidenceTaskCommand cmd = c.dataAs(EvidenceTaskCommand.class);
        publish(EventTypes.EVIDENCE_COMPLETED, c, new EvidenceCompleted(cmd.taskId(), evidence(cmd),
                new Usage(2, 1, 30_000, 1_000, 0), 1));
        awaitState(id, "NEEDS_REVIEW");
        assertEquals("BUDGET_EXHAUSTED", run(id).getString("outcome"));
    }

    @Test
    void allEvidenceFailingMeansQuorumNotMet() throws Exception {
        UUID id = submit(body("SUCCESS", "PROPOSE_ONLY", "NONE"));
        for (CloudEvent c : evidenceCommands(id, 3)) {
            publish(EventTypes.TASK_FAILED, c, new TaskFailed(c.taskId(), TaskKind.EVIDENCE, FailureReason.TOOL_DENIED,
                    false, "denied", 1, Usage.ZERO));
        }
        awaitState(id, "NEEDS_REVIEW");
        assertEquals("QUORUM_NOT_MET", run(id).getString("outcome"));
    }

    @Test
    void overdueRunIsTimedOutBySweeperAndLateResultsAreIgnored() throws Exception {
        UUID id = submit(body("SUCCESS", "PROPOSE_ONLY", "NONE"));
        List<CloudEvent> cmds = evidenceCommands(id, 3);
        QuarkusTransaction.requiringNew().run(() -> {
            Run r = store.lock(id).orElseThrow();
            r.deadline = java.time.Instant.now().minusSeconds(1);
            store.update(r);
        });
        awaitState(id, "TIMED_OUT");
        CloudEvent c = cmds.getFirst();
        EvidenceTaskCommand cmd = c.dataAs(EvidenceTaskCommand.class);
        publish(EventTypes.EVIDENCE_COMPLETED, c, new EvidenceCompleted(cmd.taskId(), evidence(cmd), Usage.ZERO, 1));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> given().get("/v1/runs/" + id + "/timeline").then()
                .extract().jsonPath().getList("items.kind").contains("IGNORED_LATE"));
        assertEquals("TIMED_OUT", run(id).getString("state"));
        assertEquals("EXPIRED", run(id).getString("tasks[0].state"));
    }

    @Test
    void committedRunIsPublishedAfterTheRelayRecovers() throws Exception {
        relay.stop();
        UUID id;
        try {
            id = submit(body("SUCCESS", "PROPOSE_ONLY", "NONE"));
            Thread.sleep(1500);
            assertEquals("SUBMITTED", run(id).getString("state"), "nothing published while the relay is down");
            long pending = QuarkusTransaction.requiringNew().call(() ->
                    store.scalar("select count(*) from outbox_event where published_at is null"));
            assertTrue(pending >= 2, "command and state event are durably queued");
        } finally {
            relay.startRelay();
        }
        awaitState(id, "GATHERING_EVIDENCE");
    }

    @Test
    void burstIsBoundedAndStoppable() {
        given().contentType(ContentType.JSON).body(Map.of("runs", 500)).post("/v1/bursts").then().statusCode(202)
                .body("runs", org.hamcrest.Matchers.equalTo(12));
        given().contentType(ContentType.JSON).body(Map.of()).post("/v1/bursts").then().statusCode(409);
        given().post("/v1/admin/reset").then().statusCode(409);
        given().post("/v1/bursts/stop").then().statusCode(200).body("active", org.hamcrest.Matchers.equalTo(false));
        given().get("/v1/bursts/current").then().statusCode(200).body("active", org.hamcrest.Matchers.equalTo(false));
    }
}
