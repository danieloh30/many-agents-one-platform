package io.github.danieloh30.maop.testing;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Real NATS JetStream server for integration tests (same image as the deployment). */
public class NatsTestResource implements QuarkusTestResourceLifecycleManager {

    public static final String IMAGE = "docker.io/library/nats:2.15.3-alpine";
    private static GenericContainer<?> nats;

    @SuppressWarnings("resource")
    public static synchronized GenericContainer<?> container() {
        if (nats == null) {
            nats = new GenericContainer<>(IMAGE).withCommand("-js", "-m", "8222").withExposedPorts(4222, 8222)
                    .waitingFor(Wait.forHttp("/healthz").forPort(8222));
            nats.start();
        }
        return nats;
    }

    public static String url() {
        GenericContainer<?> c = container();
        return "nats://" + c.getHost() + ":" + c.getMappedPort(4222);
    }

    @Override
    public Map<String, String> start() {
        return Map.of("maop.nats.url", url());
    }

    @Override
    public void stop() {
        // Shared across test classes in one JVM; Testcontainers/Ryuk or JVM exit cleans up.
    }
}
