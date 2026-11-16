package io.github.danieloh30.maop.console;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class Lifecycle {

    private static final Logger LOG = Logger.getLogger(Lifecycle.class);

    @Inject
    EventHub hub;
    @Inject
    SnapshotService snapshots;

    private volatile boolean started;

    void start(@Observes @Priority(100) StartupEvent ev) {
        hub.addListener(snapshots::observe);
        try {
            hub.start();
            started = true;
        } catch (Exception e) {
            LOG.errorf("Event stream subscription failed: %s", e.getMessage());
        }
    }

    @Scheduled(every = "${maop.snapshot.interval:2s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void snapshot() {
        if (started) {
            hub.broadcast("ops-snapshot", snapshots.refresh());
        }
    }

    @Scheduled(every = "15s")
    void heartbeat() {
        hub.heartbeat();
    }
}
