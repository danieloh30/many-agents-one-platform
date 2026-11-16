package io.github.danieloh30.maop.triage;

import io.github.danieloh30.maop.testing.FakeMtlsServer;
import io.github.danieloh30.maop.testing.NatsTestResource;
import io.github.danieloh30.maop.testing.TestPki;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** NATS + a fake tool-service (mTLS) that accepts approval grants after two transient failures. */
public class TriageResources implements QuarkusTestResourceLifecycleManager {

    public static final Path PKI = Path.of("target", "test-pki").toAbsolutePath();
    public static final String TOOL_ID = "spiffe://maop.test/ns/maop/sa/tool-service";
    public static final AtomicInteger GRANT_FAILURES_LEFT = new AtomicInteger(2);
    public static FakeMtlsServer toolService;

    @Override
    public Map<String, String> start() {
        try {
            TestPki pki = new TestPki("triage test CA");
            pki.issue(PKI.resolve("tool-service"), TOOL_ID, "localhost");
            pki.issue(PKI.resolve("triage-agent"), "spiffe://maop.test/ns/maop/sa/triage-agent");
            toolService = new FakeMtlsServer(PKI.resolve("tool-service"), req -> {
                if (req.path().startsWith("/v1/approvals/") && GRANT_FAILURES_LEFT.getAndDecrement() > 0) {
                    return new FakeMtlsServer.Reply(503, "{}", 0);
                }
                return new FakeMtlsServer.Reply(201, "{\"created\":true}", 0);
            });
            return Map.of("maop.nats.url", NatsTestResource.url(), "maop.spiffe.dir", PKI.resolve("triage-agent").toString(),
                    "maop.tool-service.url", toolService.url(), "maop.tool-service.spiffe-id", TOOL_ID,
                    "maop.sweeper.interval", "1s");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
        if (toolService != null) {
            toolService.close();
        }
    }
}
