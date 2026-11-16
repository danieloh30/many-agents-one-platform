package io.github.danieloh30.maop.triage;

import io.github.danieloh30.maop.common.contract.Outcome;
import io.github.danieloh30.maop.common.contract.RunState;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.outbox.Outbox;
import io.opentelemetry.context.Scope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

/** Deadline backstop for every lost-message case, plus burst completion. Safe with several replicas (SKIP LOCKED). */
@ApplicationScoped
public class Sweeper {

    private static final Logger LOG = Logger.getLogger(Sweeper.class);

    @Inject
    RunStore store;
    @Inject
    Workflow workflow;
    @Inject
    Outbox outbox;

    @Scheduled(every = "${maop.sweeper.interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (UUID id : store.overdue(20)) {
                    Run r = store.lock(id).orElseThrow();
                    try (Scope ignored = workflow.runTrace(r, "run.deadline")) {
                        if (r.state == RunState.AWAITING_APPROVAL) {
                            workflow.transition(r, RunState.COMPLETED, Outcome.APPROVAL_EXPIRED,
                                    "No approval decision within the approval window");
                        } else {
                            workflow.transition(r, RunState.TIMED_OUT, Outcome.DEADLINE_EXCEEDED,
                                    "Run deadline passed in state " + r.state);
                        }
                    }
                }
                store.activeBurst().ifPresent(b -> {
                    UUID burstId = UUID.fromString(b.get("burstId").asText());
                    if (store.activeRunsOfBurst(burstId).isEmpty()) {
                        store.endBurst(burstId);
                        b.put("active", false);
                        b.put("completed", b.get("runs").asInt());
                        outbox.event(EventTypes.eventSubject(EventTypes.BURST_CHANGED),
                                CloudEvent.builder(Workflow.SERVICE, EventTypes.BURST_CHANGED).data(b).build());
                    }
                });
            });
        } catch (Exception e) {
            LOG.warnf("Sweeper iteration failed: %s", e.getMessage());
        }
    }
}
