package io.github.danieloh30.maop.tools;

import static io.github.danieloh30.maop.tools.ToolClients.call;
import static io.github.danieloh30.maop.tools.ToolClients.invoke;
import static io.github.danieloh30.maop.tools.ToolClients.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.model.ActionDigests;
import io.github.danieloh30.maop.common.model.ActionType;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.tls.SpiffeHttpClient;
import io.github.danieloh30.maop.common.tls.SvidSource;
import io.github.danieloh30.maop.testing.NatsTestResource;
import io.github.danieloh30.maop.testing.TestPki;
import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.SSLContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = ToolServiceResources.class, restrictToAnnotatedClass = true)
class ToolServiceTest {

    @Inject
    AgroalDataSource ds;

    private static UUID run() {
        return Ids.v7();
    }

    private long count(String sql) throws Exception {
        try (var c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void evidenceWorkerReadsSyntheticDataWithVerifiedIdentity() throws Exception {
        HttpResponse<String> r = invoke("evidence-worker", "deployment.get_history", "k-" + Ids.v7(), run(), Ids.v7(),
                Map.of("service", "checkout", "limit", 3), null);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode b = json(r);
        assertEquals("EXECUTED", b.get("status").asText());
        assertEquals(false, b.get("replayed").asBoolean());
        assertEquals(ToolServiceResources.id("evidence-worker"), b.at("/caller/spiffeId").asText());
        assertEquals(true, b.at("/decision/allow").asBoolean());
        assertTrue(b.at("/decision/decisionId").asText().length() > 0, "OPA decision id recorded");
        assertEquals("SYNTHETIC", b.at("/result/dataOrigin").asText());
        assertEquals("2.14.0", b.at("/result/deployments/0/version").asText());
    }

    @Test
    void duplicateRequestIsReplayedNotReExecuted() throws Exception {
        UUID run = run();
        UUID task = Ids.v7();
        String key = "dup-" + Ids.v7();
        Map<String, Object> args = Map.of("service", "checkout");
        HttpResponse<String> first = invoke("evidence-worker", "catalog.get_service", key, run, task, args, null);
        HttpResponse<String> second = invoke("evidence-worker", "catalog.get_service", key, run, task, args, null);
        assertEquals(200, second.statusCode());
        assertEquals(json(first).get("invocationId"), json(second).get("invocationId"));
        assertTrue(json(second).get("replayed").asBoolean());
        JsonNode audit = json(call("ops-console", "GET", "/v1/invocations?runId=" + run, null)).at("/items/0");
        assertEquals(2, audit.get("requests").asInt());
        assertEquals(1, audit.get("executions").asInt());
    }

    @Test
    void sameKeyWithDifferentArgumentsIsRejected() throws Exception {
        String key = "conflict-" + Ids.v7();
        UUID run = run();
        UUID task = Ids.v7();
        invoke("evidence-worker", "catalog.get_service", key, run, task, Map.of("service", "checkout"), null);
        HttpResponse<String> r = invoke("evidence-worker", "catalog.get_service", key, run, task,
                Map.of("service", "cart"), null);
        assertEquals(409, r.statusCode());
        assertEquals("IDEMPOTENCY_KEY_REUSED", json(r).get("code").asText());
    }

    @Test
    void concurrentDuplicatesExecuteExactlyOnce() throws Exception {
        UUID run = run();
        UUID task = Ids.v7();
        String key = "race-" + Ids.v7();
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> invoke("evidence-worker", "telemetry.get_error_summary", key, run, task,
                    Map.of("service", "checkout", "windowMinutes", 30), null).statusCode());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertEquals(200, f.get());
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(1, count("select count(*) from tool_invocation where run_id = '" + run + "'"));
        assertEquals(8, count("select requests from tool_invocation where run_id = '" + run + "'"));
        assertEquals(1, count("select executions from tool_invocation where run_id = '" + run + "'"));
    }

    @Test
    void unapprovedRemediationIsDeniedBeforeAnySideEffect() throws Exception {
        UUID run = run();
        HttpResponse<String> r = invoke("recommendation-agent", "remediation.rollback_deployment", "rb-" + Ids.v7(), run,
                Ids.v7(), Map.of("service", "checkout", "toVersion", "2.13.2"), null);
        assertEquals(403, r.statusCode(), r.body());
        JsonNode b = json(r);
        assertEquals("POLICY_DENIED", b.get("code").asText());
        assertEquals("DENIED", b.get("status").asText());
        assertEquals(List.of("approval_required"), List.of(b.at("/decision/reasons/0").asText()));
        assertEquals(ToolServiceResources.id("recommendation-agent"), b.at("/caller/spiffeId").asText());
        assertEquals(0, count("select count(*) from simulated_action where run_id = '" + run + "'"));
        JsonNode audit = json(call("ops-console", "GET", "/v1/invocations?runId=" + run, null)).at("/items/0");
        assertEquals("DENIED", audit.get("status").asText());
        assertEquals(0, audit.get("executions").asInt());
        assertTrue(audit.get("sideEffect").isNull());
    }

    @Test
    void approvedRemediationExecutesOnceAndOnlyForTheApprovedAction() throws Exception {
        UUID run = run();
        UUID approval = Ids.v7();
        String digest = ActionDigests.digest(run, ActionType.ROLLBACK_DEPLOYMENT, "checkout", Map.of("toVersion", "2.13.2"));
        Map<String, Object> grant = Map.of("runId", run, "actionDigest", digest, "approver", "demo-operator",
                "approvedAt", Instant.now().toString(), "expiresAt", Instant.now().plusSeconds(600).toString());
        // Only triage may register approvals.
        assertEquals(403, call("recommendation-agent", "PUT", "/v1/approvals/" + approval, grant).statusCode());
        assertEquals(201, call("triage-agent", "PUT", "/v1/approvals/" + approval, grant).statusCode());
        assertEquals(200, call("triage-agent", "PUT", "/v1/approvals/" + approval, grant).statusCode());

        // The approval does not authorise a different action.
        HttpResponse<String> other = invoke("recommendation-agent", "remediation.rollback_deployment", "rb-o-" + Ids.v7(),
                run, Ids.v7(), Map.of("service", "checkout", "toVersion", "2.12.4"), approval);
        assertEquals(403, other.statusCode());
        assertEquals("approval_mismatch", json(other).at("/decision/reasons/0").asText());

        // The evidence worker cannot use it at all.
        HttpResponse<String> ev = invoke("evidence-worker", "remediation.rollback_deployment", "rb-e-" + Ids.v7(), run,
                Ids.v7(), Map.of("service", "checkout", "toVersion", "2.13.2"), approval);
        assertEquals(403, ev.statusCode());
        assertEquals("caller_not_permitted", json(ev).at("/decision/reasons/0").asText());

        String key = "rb-ok-" + Ids.v7();
        UUID task = Ids.v7();
        HttpResponse<String> ok = invoke("recommendation-agent", "remediation.rollback_deployment", key, run, task,
                Map.of("service", "checkout", "toVersion", "2.13.2"), approval);
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(true, json(ok).at("/result/simulated").asBoolean());
        invoke("recommendation-agent", "remediation.rollback_deployment", key, run, task,
                Map.of("service", "checkout", "toVersion", "2.13.2"), approval);
        assertEquals(1, count("select count(*) from simulated_action where run_id = '" + run + "'"));
    }

    @Test
    void connectionWithoutClientCertificateIsRefused() throws Exception {
        SvidSource.Material m = new SvidSource(ToolServiceResources.dir("ops-console")).current();
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new javax.net.ssl.TrustManager[] { trustAll() }, null);
        HttpClient anonymous = HttpClient.newBuilder().sslContext(ctx).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(ToolClients.BASE + "/v1/tools"))
                .header("X-Spiffe-Id", ToolServiceResources.id("ops-console")) // a forged header is never trusted
                .header("X-Forwarded-Client-Cert", "By=spiffe://maop.test/ns/maop/sa/ops-console")
                .GET().build();
        assertThrows(IOException.class, () -> anonymous.send(req, HttpResponse.BodyHandlers.ofString()));
        assertTrue(m.spiffeId().startsWith("spiffe://"));
    }

    @Test
    void certificateFromAnotherAuthorityIsRefused() {
        SpiffeHttpClient attacker = ToolClients.as("attacker");
        assertThrows(IOException.class, () -> attacker.send("GET", URI.create(ToolClients.BASE + "/v1/tools"), null,
                Map.of(), Duration.ofSeconds(5)));
    }

    @Test
    void invalidSvidsAreRejected() throws Exception {
        // Two URI SANs: the TLS chain is valid but it is not an SVID.
        HttpClient http = HttpClient.newBuilder().sslContext(rawClientContext("two-sans")).build();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(ToolClients.BASE + "/v1/tools")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, r.statusCode());
        // Wrong trust domain from the same CA.
        HttpClient foreign = HttpClient.newBuilder().sslContext(rawClientContext("foreign-domain")).build();
        HttpResponse<String> f = foreign.send(HttpRequest.newBuilder(URI.create(ToolClients.BASE + "/v1/tools")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, f.statusCode());
        assertEquals("UNTRUSTED_DOMAIN", json(f).get("code").asText());
    }

    @Test
    void unknownWorkloadInTrustDomainIsDeniedByPolicy() throws Exception {
        HttpResponse<String> r = invoke("intruder", "catalog.get_service", "intr-" + Ids.v7(), run(), Ids.v7(),
                Map.of("service", "checkout"), null);
        assertEquals(403, r.statusCode());
        assertEquals("caller_not_permitted", json(r).at("/decision/reasons/0").asText());
    }

    @Test
    void auditIsOnlyReadableByTheConsole() throws Exception {
        assertEquals(403, call("evidence-worker", "GET", "/v1/invocations", null).statusCode());
        assertEquals(200, call("ops-console", "GET", "/v1/invocations", null).statusCode());
    }

    @Test
    void invalidArgumentsAreRejectedWithoutExecution() throws Exception {
        UUID run = run();
        HttpResponse<String> r = invoke("evidence-worker", "deployment.get_history", "bad-" + Ids.v7(), run, Ids.v7(),
                Map.of("service", "checkout", "limit", 500), null);
        assertEquals(422, r.statusCode());
        assertEquals(0, count("select count(*) from tool_invocation where run_id = '" + run + "'"));
    }

    @Test
    void traceContextFlowsFromRequestIntoTheAuditEvent() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e" + Integer.toHexString(0x1000 + (int) (Math.random() * 0xefff));
        String traceparent = "00-" + traceId + "-00f067aa0ba902b7-01";
        UUID run = run();
        UUID task = Ids.v7();
        try (Connection nc = Nats.connect(NatsTestResource.url())) {
            JetStreamSubscription sub = nc.jetStream().subscribe(null, PullSubscribeOptions.builder()
                    .stream(Subjects.EVENTS_STREAM)
                    .configuration(ConsumerConfiguration.builder().filterSubject("maop.events.v1.tool.invocation.completed")
                            .deliverPolicy(DeliverPolicy.New).build())
                    .build());
            Map<String, Object> body = Map.of("runId", run, "taskId", task, "arguments", Map.of("service", "checkout"));
            HttpResponse<String> r = ToolClients.as("evidence-worker").send("POST",
                    URI.create(ToolClients.BASE + "/v1/tools/catalog.get_service/invocations"),
                    io.github.danieloh30.maop.common.json.Json.write(body),
                    Map.of("Idempotency-Key", "trace-" + Ids.v7(), "traceparent", traceparent), Duration.ofSeconds(5));
            assertEquals(200, r.statusCode());
            CloudEvent found = null;
            Instant until = Instant.now().plusSeconds(10);
            while (found == null && Instant.now().isBefore(until)) {
                for (Message m : sub.fetch(10, Duration.ofSeconds(1))) {
                    CloudEvent e = CloudEventCodec.fromBinary(m.getHeaders(), m.getData());
                    m.ack();
                    if (run.equals(e.runId())) {
                        found = e;
                    }
                }
            }
            assertTrue(found != null, "tool.invocation.completed event published via outbox");
            String tp = found.ext(CloudEvent.EXT_TRACEPARENT);
            assertTrue(tp != null && tp.contains(traceId), "event continues the caller's trace: " + tp);
            assertEquals(ToolServiceResources.id("evidence-worker"), found.data().get("callerSpiffeId").asText());
        }
    }

    @Test
    void rotatedServerAndClientSvidsAreUsedWithoutRestart() throws Exception {
        SpiffeHttpClient client = ToolClients.as("ops-console");
        String before = peerSerial(client);
        TestPki pki = TestPki.load(ToolServiceResources.PKI.resolve("ca"));
        pki.issue(ToolServiceResources.dir("tool-service"), ToolServiceResources.id("tool-service"), "tool-service", "localhost");
        pki.issue(ToolServiceResources.dir("ops-console"), ToolServiceResources.id("ops-console"), "ops-console", "localhost");
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .until(() -> !before.equals(peerSerial(ToolClients.as("ops-console"))));
        assertNotEquals(before, peerSerial(ToolClients.as("ops-console")));
        assertEquals(200, client.send("GET", URI.create(ToolClients.BASE + "/v1/tools"), null, Map.of(),
                Duration.ofSeconds(5)).statusCode());
    }

    private static String peerSerial(SpiffeHttpClient client) throws Exception {
        HttpResponse<String> r = client.send("GET", URI.create(ToolClients.BASE + "/v1/tools"), null, Map.of(),
                Duration.ofSeconds(5));
        X509Certificate peer = (X509Certificate) r.sslSession().orElseThrow().getPeerCertificates()[0];
        return peer.getSerialNumber().toString(16);
    }

    private static SSLContext rawClientContext(String workload) throws Exception {
        SvidSource.Material m = new SvidSource(ToolServiceResources.dir(workload)).currentUnchecked();
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("k", m.key(), new char[0], m.chain());
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance("PKIX");
        kmf.init(ks, new char[0]);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new javax.net.ssl.TrustManager[] { trustAll() }, null);
        return ctx;
    }

    private static javax.net.ssl.X509TrustManager trustAll() {
        return new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            public void checkServerTrusted(X509Certificate[] c, String a) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
