package io.github.danieloh30.maop.tools;

import io.github.danieloh30.maop.testing.NatsTestResource;
import io.github.danieloh30.maop.testing.TestPki;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Starts a SPIRE-like test PKI (files under target/test-pki), a real OPA with the repository policy, and NATS.
 * Workload SVIDs use the same SPIFFE ID layout as the deployment: spiffe://maop.test/ns/maop/sa/&lt;name&gt;.
 */
public class ToolServiceResources implements QuarkusTestResourceLifecycleManager {

    public static final String TD = "maop.test";
    public static final Path PKI = Path.of("target", "test-pki").toAbsolutePath();
    private static GenericContainer<?> opa;

    public static String id(String workload) {
        return "spiffe://" + TD + "/ns/maop/sa/" + workload;
    }

    public static Path dir(String workload) {
        return PKI.resolve(workload);
    }

    @Override
    @SuppressWarnings("resource")
    public Map<String, String> start() {
        TestPki pki = new TestPki("maop.test CA").save(PKI.resolve("ca"));
        for (String w : List.of("tool-service", "evidence-worker", "recommendation-agent", "triage-agent", "ops-console",
                "intruder")) {
            pki.issue(dir(w), id(w), w, "localhost");
        }
        // A certificate from the trusted CA but with two URI SANs (not a valid SVID).
        pki.issue(dir("two-sans"), Duration.ofHours(1), List.of(id("evidence-worker"), id("ops-console")), List.of());
        // A workload from a different trust domain signed by the same CA.
        pki.issue(dir("foreign-domain"), "spiffe://evil.example/ns/maop/sa/evidence-worker");
        // A completely separate CA (attacker).
        new TestPki("attacker CA").issue(dir("attacker"), id("evidence-worker"));

        Path policy = Path.of(System.getProperty("maop.policy.dir", "../policy/maop")).toAbsolutePath();
        if (!Files.isDirectory(policy)) {
            throw new IllegalStateException("Policy dir not found: " + policy);
        }
        opa = new GenericContainer<>("docker.io/openpolicyagent/opa:1.22.4-static")
                .withCopyFileToContainer(MountableFile.forHostPath(policy.resolve("tools.rego")), "/policy/maop/tools.rego")
                .withCopyFileToContainer(MountableFile.forHostPath(policy.resolve("data.json")), "/policy/maop/data.json")
                .withCommand("run", "--server", "--addr=0.0.0.0:8181", "--set=decision_logs.console=true", "/policy")
                .withExposedPorts(8181).waitingFor(Wait.forHttp("/health").forPort(8181));
        opa.start();

        Map<String, String> cfg = new HashMap<>();
        cfg.put("maop.spiffe.dir", dir("tool-service").toString());
        cfg.put("maop.trust-domain", TD);
        cfg.put("maop.opa.url", "http://" + opa.getHost() + ":" + opa.getMappedPort(8181));
        cfg.put("maop.nats.url", NatsTestResource.url());
        cfg.put("quarkus.tls.spiffe.reload-period", "1S");
        return cfg;
    }

    @Override
    public void stop() {
        if (opa != null) {
            opa.stop();
        }
    }
}
