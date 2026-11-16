package io.github.danieloh30.maop.recommendation;

import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.messaging.JetStreamClient;
import io.github.danieloh30.maop.common.messaging.PullWorker;
import io.github.danieloh30.maop.common.messaging.WorkerSupport;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class Lifecycle {

    @Inject
    JetStreamClient js;
    @Inject
    RecommendationHandler handler;
    @Inject
    WorkerSupport worker;

    @ConfigProperty(name = "maop.worker.concurrency", defaultValue = "2")
    int concurrency;

    private PullWorker pull;

    void start(@Observes @Priority(100) StartupEvent ev) {
        worker.modelCallEvents();
        pull = new PullWorker(js, js.consumerSpec(Subjects.CONSUMER_RECOMMENDATION), concurrency, handler);
        pull.start();
        worker.startHeartbeat(pull::activeTasks);
    }

    void stop(@Observes @Priority(1) ShutdownEvent ev) {
        if (pull != null) {
            pull.stop(Duration.ofSeconds(25));
        }
        worker.stopHeartbeat();
    }
}
