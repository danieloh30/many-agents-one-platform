package io.github.danieloh30.maop.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieloh30.maop.common.contract.ActionDigests;
import io.github.danieloh30.maop.common.contract.ActionType;
import io.github.danieloh30.maop.common.contract.EvidenceTaskCommand;
import io.github.danieloh30.maop.common.contract.FaultInjection;
import io.github.danieloh30.maop.common.contract.Incident;
import io.github.danieloh30.maop.common.contract.TaskBudget;
import io.github.danieloh30.maop.common.contract.Topic;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tls.SpiffeIds;
import io.github.danieloh30.maop.common.tls.SvidSource;
import io.github.danieloh30.maop.common.tools.ToolCatalog;
import io.github.danieloh30.maop.testing.TestPki;
import io.nats.client.impl.Headers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvelopeAndIdentityTest {

    @Test
    void cloudEventBinaryModeRoundTripUsesCePrefixedHeadersAndMessageId() {
        UUID run = Ids.v7();
        UUID task = Ids.v7();
        EvidenceTaskCommand cmd = new EvidenceTaskCommand(run, task, Topic.CONFIG_DIFF, new Incident("t", "d", "checkout"),
                "q", new TaskBudget(2, 4, 800), Instant.parse("2026-01-01T12:00:00Z"), FaultInjection.NONE, "SUCCESS");
        CloudEvent e = CloudEvent.builder("triage-agent", EventTypes.EVIDENCE_REQUESTED).task(run, task)
                .extension(CloudEvent.EXT_TRACEPARENT, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .extension(CloudEvent.EXT_MODEL_MODE, "DETERMINISTIC").data(cmd).build();
        Headers h = CloudEventCodec.toHeaders(e);
        assertEquals("1.0", h.getFirst("ce-specversion"));
        assertEquals(e.id(), h.getFirst("Nats-Msg-Id"));
        assertEquals("io.github.danieloh30.maop.task.evidence.requested.v1", h.getFirst("ce-type"));
        assertEquals("/maop/triage-agent", h.getFirst("ce-source"));
        assertEquals(run.toString(), h.getFirst("ce-maoprunid"));
        assertEquals(e.ext(CloudEvent.EXT_TRACEPARENT), h.getFirst("traceparent"), "un-prefixed copy for propagators");
        CloudEvent back = CloudEventCodec.fromBinary(h, CloudEventCodec.toPayload(e));
        assertEquals(e.id(), back.id());
        assertEquals(task, back.taskId());
        assertEquals(cmd, back.dataAs(EvidenceTaskCommand.class));
        CloudEvent structured = CloudEventCodec.fromStructured(CloudEventCodec.toStructured(e));
        assertEquals(e.extensions(), structured.extensions());
        assertEquals("maop.events.v1.task.evidence.completed", EventTypes.eventSubject(EventTypes.EVIDENCE_COMPLETED));
        assertEquals(1, EventTypes.majorVersion(EventTypes.EVIDENCE_COMPLETED));
    }

    @Test
    void nonCloudEventMessagesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CloudEventCodec.fromBinary(new Headers(), "{}".getBytes()));
    }

    @Test
    void uuidV7IsMonotonicAndV5IsDeterministic() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            ids.add(Ids.v7());
        }
        List<UUID> sorted = new ArrayList<>(ids);
        sorted.sort((a, b) -> a.toString().compareTo(b.toString()));
        assertEquals(ids, sorted, "generation order equals lexical (outbox) order");
        assertEquals(7, ids.getFirst().version());
        UUID task = Ids.v7();
        assertEquals(Ids.v5(task, "x"), Ids.v5(task, "x"));
        assertNotEquals(Ids.v5(task, "x"), Ids.v5(task, "y"));
        assertEquals(5, Ids.v5(task, "x").version());
    }

    @Test
    void canonicalJsonAndActionDigestIgnoreKeyOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", Map.of("z", true, "y", "x"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Map.of("y", "x", "z", true));
        b.put("b", 1);
        assertEquals("{\"a\":{\"y\":\"x\",\"z\":true},\"b\":1}", Json.canonical(a));
        assertEquals(Json.digest(a), Json.digest(b));
        UUID run = Ids.v7();
        String d1 = ActionDigests.digest(run, ActionType.ROLLBACK_DEPLOYMENT, "checkout", Map.of("toVersion", "2.13.2"));
        assertNotEquals(d1, ActionDigests.digest(run, ActionType.ROLLBACK_DEPLOYMENT, "checkout", Map.of("toVersion", "2.12.4")));
        assertNotEquals(d1, ActionDigests.digest(Ids.v7(), ActionType.ROLLBACK_DEPLOYMENT, "checkout", Map.of("toVersion", "2.13.2")));
    }

    @Test
    void extractsFirstJsonObjectFromModelText() {
        assertEquals("{\"a\":\"}\"}", Json.extractObject("Sure:\n```json\n{\"a\":\"}\"}\n``` done"));
        assertEquals(null, Json.extractObject("no json here"));
    }

    @Test
    void svidParsingRequiresExactlyOneSpiffeUri() throws Exception {
        Path dir = Files.createTempDirectory("svid");
        TestPki pki = new TestPki("unit CA");
        pki.issue(dir.resolve("ok"), "spiffe://maop.test/ns/maop/sa/evidence-worker");
        SvidSource.Material m = new SvidSource(dir.resolve("ok")).current();
        assertEquals("spiffe://maop.test/ns/maop/sa/evidence-worker", m.spiffeId());
        assertEquals("maop.test", SpiffeIds.trustDomain(m.spiffeId()));
        pki.issue(dir.resolve("two"), Duration.ofHours(1),
                List.of("spiffe://maop.test/a", "spiffe://maop.test/b"), List.of());
        assertThrows(IllegalStateException.class, () -> new SvidSource(dir.resolve("two")).current());
        assertTrue(SpiffeIds.inTrustDomain("spiffe://maop.test/x", "maop.test"));
    }

    @Test
    void toolArgumentsAreValidatedAgainstTheCatalogue() {
        ToolCatalog.Tool history = ToolCatalog.find("deployment.get_history").orElseThrow();
        assertEquals(null, ToolCatalog.validate(history, Map.of("service", "checkout", "limit", 3)));
        assertTrue(ToolCatalog.validate(history, Map.of("service", "checkout", "limit", 50)).contains("between"));
        assertTrue(ToolCatalog.validate(history, Map.of("limit", 3)).contains("required"));
        assertTrue(ToolCatalog.validate(history, Map.of("service", "checkout", "rm", "-rf")).contains("unknown"));
    }

    @Test
    void budgetTrackerStopsAtTheLimit() {
        BudgetTracker b = new BudgetTracker(new TaskBudget(2, 1, 100));
        b.beforeModelCall();
        b.beforeModelCall();
        TaskFailure f = assertThrows(TaskFailure.class, b::beforeModelCall);
        assertEquals(io.github.danieloh30.maop.common.contract.FailureReason.BUDGET_EXHAUSTED, f.reason());
        b.beforeToolCall();
        assertThrows(TaskFailure.class, b::beforeToolCall);
        b.tokens(10, 5);
        assertEquals(2, b.usage().modelCalls());
        assertEquals(15, b.usage().totalTokens());
    }

    @Test
    void deadlinesProduceBoundedTimeoutsAndRefuseLateCalls() {
        Duration t = Deadlines.callTimeout(Instant.now().plusSeconds(60), Duration.ofSeconds(20), "x");
        assertEquals(Duration.ofSeconds(20), t);
        Duration shorter = Deadlines.callTimeout(Instant.now().plusSeconds(10), Duration.ofSeconds(20), "x");
        assertTrue(shorter.compareTo(Duration.ofSeconds(9)) < 0);
        TaskFailure f = assertThrows(TaskFailure.class, () -> Deadlines.callTimeout(Instant.now().plusSeconds(2),
                Duration.ofSeconds(20), "x"));
        assertEquals(false, f.retryable());
    }
}
