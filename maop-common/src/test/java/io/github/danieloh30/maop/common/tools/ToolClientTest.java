package io.github.danieloh30.maop.common.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.testing.FakeMtlsServer;
import io.github.danieloh30.maop.testing.TestPki;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ToolClientTest {

    static Path dir;
    static final String SERVER = "spiffe://maop.test/ns/maop/sa/tool-service";

    @BeforeAll
    static void pki() throws Exception {
        dir = Files.createTempDirectory("toolclient");
        TestPki pki = new TestPki("toolclient CA");
        pki.issue(dir.resolve("server"), SERVER, "localhost");
        pki.issue(dir.resolve("client"), "spiffe://maop.test/ns/maop/sa/evidence-agent");
        pki.issue(dir.resolve("impostor"), "spiffe://maop.test/ns/maop/sa/evidence-agent", "localhost");
    }

    private static ToolClient client(String url, String expected) {
        ToolClient c = new ToolClient();
        c.spiffeDir = dir.resolve("client").toString();
        c.baseUrl = url;
        c.expectedServerId = expected;
        c.connectTimeout = Duration.ofSeconds(1);
        c.readTimeout = Duration.ofMillis(800);
        c.maxRetries = 2;
        return c;
    }

    private static final String OK = "{\"invocationId\":\"i-1\",\"status\":\"EXECUTED\",\"replayed\":false,"
            + "\"decision\":{\"allow\":true,\"decisionId\":\"d\",\"policyRevision\":\"r\",\"reasons\":[]},\"result\":{}}";

    @Test
    void transientUnavailabilityIsRetriedWithTheSameIdempotencyKey() throws Exception {
        AtomicInteger n = new AtomicInteger();
        try (FakeMtlsServer s = new FakeMtlsServer(dir.resolve("server"),
                r -> n.incrementAndGet() < 3 ? new FakeMtlsServer.Reply(503, "{}", 0) : new FakeMtlsServer.Reply(200, OK, 0))) {
            ToolResult r = client(s.url(), SERVER).invoke(Ids.v7(), Ids.v7(), "catalog.get_service",
                    Map.of("service", "checkout"), null, Instant.now().plusSeconds(30), null);
            assertEquals("EXECUTED", r.status());
            assertEquals(3, s.requests.size());
            assertEquals(1, s.requests.stream().map(q -> q.headers().get("Idempotency-key")).distinct().count(),
                    "every retry carries the same idempotency key");
        }
    }

    @Test
    void retriesAreBounded() throws Exception {
        try (FakeMtlsServer s = new FakeMtlsServer(dir.resolve("server"), r -> new FakeMtlsServer.Reply(503, "{}", 0))) {
            TaskFailure f = assertThrows(TaskFailure.class, () -> client(s.url(), SERVER).invoke(Ids.v7(), Ids.v7(),
                    "catalog.get_service", Map.of("service", "checkout"), null, Instant.now().plusSeconds(30), null));
            assertEquals(FailureReason.TOOL_UNAVAILABLE, f.reason());
            assertTrue(f.retryable());
            assertEquals(3, s.requests.size(), "1 attempt + 2 retries");
        }
    }

    @Test
    void slowToolTimesOut() throws Exception {
        try (FakeMtlsServer s = new FakeMtlsServer(dir.resolve("server"), r -> new FakeMtlsServer.Reply(200, OK, 3000))) {
            long start = System.nanoTime();
            TaskFailure f = assertThrows(TaskFailure.class, () -> client(s.url(), SERVER).invoke(Ids.v7(), Ids.v7(),
                    "catalog.get_service", Map.of("service", "checkout"), null, Instant.now().plusSeconds(30), null));
            assertEquals(FailureReason.TOOL_UNAVAILABLE, f.reason());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 4000, "bounded by read timeout x attempts");
        }
    }

    @Test
    void policyDenialIsAResultNotARetry() throws Exception {
        String denied = "{\"code\":\"POLICY_DENIED\",\"invocationId\":\"i-2\",\"status\":\"DENIED\","
                + "\"decision\":{\"allow\":false,\"decisionId\":\"d\",\"policyRevision\":\"r\",\"reasons\":[\"approval_required\"]}}";
        try (FakeMtlsServer s = new FakeMtlsServer(dir.resolve("server"), r -> new FakeMtlsServer.Reply(403, denied, 0))) {
            ToolResult r = client(s.url(), SERVER).invoke(Ids.v7(), Ids.v7(), "remediation.rollback_deployment",
                    Map.of("service", "checkout", "toVersion", "2.13.2"), null, Instant.now().plusSeconds(30), null);
            assertTrue(r.denied());
            assertEquals("approval_required", r.decision().reasons().getFirst());
            assertEquals(1, s.requests.size());
        }
    }

    @Test
    void serverWithTheWrongSpiffeIdIsRejected() throws Exception {
        try (FakeMtlsServer s = new FakeMtlsServer(dir.resolve("impostor"), r -> new FakeMtlsServer.Reply(200, OK, 0))) {
            TaskFailure f = assertThrows(TaskFailure.class, () -> client(s.url(), SERVER).invoke(Ids.v7(), Ids.v7(),
                    "catalog.get_service", Map.of("service", "checkout"), null, Instant.now().plusSeconds(30), null));
            assertEquals(FailureReason.TOOL_UNAVAILABLE, f.reason());
            assertTrue(s.requests.isEmpty(), "no request reached an impostor server");
        }
    }

    @Test
    void idempotencyKeyIsStablePerTaskToolAndArguments() {
        UUID task = Ids.v7();
        assertEquals(ToolClient.idempotencyKey(task, "t", Map.of("a", 1, "b", 2), null),
                ToolClient.idempotencyKey(task, "t", Map.of("b", 2, "a", 1), null));
        assertTrue(!ToolClient.idempotencyKey(task, "t", Map.of("a", 1), null)
                .equals(ToolClient.idempotencyKey(task, "t", Map.of("a", 2), null)));
    }
}
