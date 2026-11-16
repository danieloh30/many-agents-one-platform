package io.github.danieloh30.maop.tools.service;

import io.github.danieloh30.maop.outbox.OutboxRelay;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class Lifecycle {

    @Inject
    OutboxRelay relay;

    void start(@Observes StartupEvent ev) {
        relay.startRelay();
    }

    void stop(@Observes ShutdownEvent ev) {
        relay.stop();
    }
}
