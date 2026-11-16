package io.github.danieloh30.maop.common.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class NatsHealthCheck implements HealthCheck {

    @Inject
    JetStreamClient client;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("nats").status(client.connected()).build();
    }
}
