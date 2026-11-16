package io.github.danieloh30.maop.triage;

import io.github.danieloh30.maop.common.RunContext;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.messaging.JetStreamClient;
import io.github.danieloh30.maop.common.messaging.PullWorker;
import io.github.danieloh30.maop.common.model.ModelGateway;
import io.github.danieloh30.maop.outbox.OutboxRelay;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class Lifecycle {

    @Inject
    JetStreamClient js;
    @Inject
    OutboxRelay relay;
    @Inject
    TriageWorker triageWorker;
    @Inject
    ResultFolder resultFolder;
    @Inject
    AdvisoryListener advisories;
    @Inject
    ModelGateway model;

    @ConfigProperty(name = "maop.workers.enabled", defaultValue = "true")
    boolean workersEnabled;

    private PullWorker triage;
    private PullWorker results;

    void start(@Observes @Priority(100) StartupEvent ev) {
        model.addListener(call -> {
            RunContext.Current ctx = RunContext.get();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("service", Workflow.SERVICE);
            d.put("taskId", ctx == null || ctx.taskId() == null ? null : ctx.taskId().toString());
            d.put("purpose", call.purpose());
            d.put("model", call.model());
            d.put("mode", call.mode());
            d.put("durationMs", call.durationMs());
            d.put("inputTokens", call.inputTokens());
            d.put("outputTokens", call.outputTokens());
            d.put("status", "OK");
            CloudEvent e = CloudEvent.builder(Workflow.SERVICE, EventTypes.MODEL_CALL_COMPLETED)
                    .task(ctx == null ? null : ctx.runId(), ctx == null ? null : ctx.taskId())
                    .extension(CloudEvent.EXT_MODEL_MODE, call.mode()).data(d).build();
            js.publishQuietly(EventTypes.eventSubject(EventTypes.MODEL_CALL_COMPLETED), e);
        });
        relay.startRelay();
        if (workersEnabled) {
            triage = new PullWorker(js, js.consumerSpec(Subjects.CONSUMER_TRIAGE), 4, triageWorker);
            results = new PullWorker(js, js.consumerSpec(Subjects.CONSUMER_TRIAGE_RESULTS), 8, resultFolder);
            triage.start();
            results.start();
            advisories.start();
        }
    }

    void stop(@Observes @Priority(1) ShutdownEvent ev) {
        if (triage != null) {
            triage.stop(Duration.ofSeconds(25));
        }
        if (results != null) {
            results.stop(Duration.ofSeconds(10));
        }
        relay.stop();
    }
}
