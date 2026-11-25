package io.github.danieloh30.maop.tools.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The synthetic scenario carries the facts an approver needs: cause, last known-good version, and reversibility. */
class SyntheticToolsTest {

    final SyntheticTools tools = new SyntheticTools();
    final Instant now = Instant.parse("2026-01-01T12:00:00Z");

    @BeforeEach
    void load() throws Exception {
        tools.load();
    }

    @Test
    void historyShowsHowEachVersionBehavedWhileLive() {
        JsonNode d = tools.execute("deployment.get_history", Map.of("service", "checkout", "limit", 2), now).path("deployments");
        assertEquals("2.14.0", d.get(0).path("version").asString());
        assertEquals(7.1, d.get(0).at("/observedWhileLive/errorRatePct").asDouble());
        assertEquals("2.13.2", d.get(1).path("version").asString());
        assertEquals(0.2, d.get(1).at("/observedWhileLive/errorRatePct").asDouble());
        assertEquals(1551, d.get(1).at("/observedWhileLive/liveMinutes").asInt());
    }

    @Test
    void diffShowsTheRollbackIsReversible() {
        JsonNode r = tools.execute("deployment.get_diff",
                Map.of("service", "checkout", "fromVersion", "2.13.2", "toVersion", "2.14.0"), now);
        assertTrue(r.path("found").asBoolean());
        assertTrue(r.path("databaseMigrations").isArray() && r.path("databaseMigrations").isEmpty());
        assertTrue(r.path("previousImageAvailable").asBoolean());
    }

    @Test
    void errorSummaryShowsTheGatewayIsSlowerThanTheNewTimeout() {
        JsonNode lat = tools.execute("telemetry.get_error_summary", Map.of("service", "checkout", "windowMinutes", 30), now)
                .at("/dependencyLatency/0");
        assertEquals("payment-gateway", lat.path("service").asString());
        assertTrue(lat.path("p99Ms").asInt() > 300, "p99 above the 300 ms client timeout");
        assertTrue(Math.abs(lat.path("p99Ms").asInt() - lat.path("baselineP99Ms").asInt()) < 50, "gateway latency unchanged");
    }
}
