package io.github.danieloh30.maop.evidence.service;

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

/** Starts the pull worker; on SIGTERM stops pulling, drains in-flight tasks, returns the rest for redelivery. */
@ApplicationScoped
public class Lifecycle {

    @Inject
    JetStreamClient js;
    @Inject
    EvidenceHandler handler;
    @Inject
    WorkerSupport worker;

    @ConfigProperty(name = "maop.worker.concurrency", defaultValue = "2")
    int concurrency;
    @ConfigProperty(name = "maop.worker.drain-timeout", defaultValue = "45s")
    Duration drain;

    private PullWorker pull;

    void start(@Observes @Priority(100) StartupEvent ev) {
        worker.modelCallEvents();
        pull = new PullWorker(js, js.consumerSpec(Subjects.CONSUMER_EVIDENCE), concurrency, handler);
        pull.start();
        worker.startHeartbeat(pull::activeTasks);
    }

    void stop(@Observes @Priority(1) ShutdownEvent ev) {
        if (pull != null) {
            pull.stop(drain);
        }
        worker.stopHeartbeat();
    }

    public int activeTasks() {
        return pull == null ? 0 : pull.activeTasks();
    }
}
