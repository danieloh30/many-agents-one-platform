package io.github.danieloh30.maop.outbox;

import tools.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.github.danieloh30.maop.common.json.Json;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Publishes committed outbox rows in id (UUIDv7, i.e. creation) order. Rows are locked with
 * {@code FOR UPDATE SKIP LOCKED} so several replicas never deliver the same row concurrently. A failing row stops
 * the batch (head-of-line), preserving per-service ordering such as "approval grant before action command".
 */
@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);

    @Inject
    AgroalDataSource dataSource;
    @Inject
    Instance<OutboxHandler> handlers;
    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "maop.outbox.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "maop.outbox.poll-interval", defaultValue = "250ms")
    Duration pollInterval;
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "maop")
    String service;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeMs = new AtomicLong();
    private Thread loop;
    private Map<String, OutboxHandler> byKind;
    private long lastHousekeeping;

    /** Called by services that own a database (not an observer, so DB-less services never instantiate it). */
    public synchronized void startRelay() {
        Gauge.builder("maop.outbox.pending", pending, AtomicLong::get).tag("service", service)
                .description("Committed outbox rows not yet published").register(registry);
        Gauge.builder("maop.outbox.oldest.age", oldestAgeMs, v -> v.get() / 1000.0).tag("service", service)
                .baseUnit("seconds").description("Age of the oldest unpublished outbox row").register(registry);
        if (enabled) {
            start();
        }
    }

    public void stop() {
        running.set(false);
        if (loop != null) {
            loop.interrupt();
        }
    }

    private void start() {
        if (running.getAndSet(true)) {
            return;
        }
        byKind = new HashMap<>();
        handlers.forEach(h -> byKind.put(h.kind(), h));
        loop = Thread.ofVirtual().name("maop-outbox-relay").start(() -> {
            while (running.get()) {
                try {
                    int n = relayOnce();
                    if (n == 0) {
                        Thread.sleep(pollInterval.toMillis());
                    }
                    housekeeping();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    LOG.warnf("Outbox relay iteration failed: %s", e.getMessage());
                    sleep(1000);
                }
            }
        });
    }

    private record Row(UUID id, String kind, String subject, String payload, int attempts) {
    }

    /** Relay one batch. Visible for tests. */
    public int relayOnce() {
        if (byKind == null) {
            byKind = new HashMap<>();
            handlers.forEach(h -> byKind.put(h.kind(), h));
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            List<Row> rows = new ArrayList<>();
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(
                            "select id, kind, subject, payload::text, attempts from outbox_event "
                                    + "where published_at is null order by id limit 50 for update skip locked")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getInt(5)));
                    }
                }
            }
            int published = 0;
            for (Row r : rows) {
                if (!available(r)) {
                    break;
                }
                OutboxHandler h = byKind.get(r.kind());
                try {
                    if (h == null) {
                        throw new IllegalStateException("No outbox handler for kind " + r.kind());
                    }
                    JsonNode payload = Json.parse(r.payload());
                    h.deliver(r.id(), r.subject(), payload);
                    mark(r.id(), null);
                    published++;
                } catch (Exception e) {
                    LOG.warnf("Outbox row %s (%s) not delivered (attempt %d): %s", r.id(), r.kind(), r.attempts() + 1,
                            e.getMessage());
                    mark(r.id(), e.getMessage() == null ? e.getClass().getName() : e.getMessage());
                    break; // preserve ordering
                }
            }
            return published;
        });
    }

    private boolean available(Row r) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("select available_at <= now() from outbox_event where id = ?")) {
            ps.setObject(1, r.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void mark(UUID id, String error) throws SQLException {
        String sql = error == null
                ? "update outbox_event set published_at = now(), attempts = attempts + 1, last_error = null where id = ?"
                : "update outbox_event set attempts = attempts + 1, last_error = ?, "
                        + "available_at = now() + (least(30000, 250 * power(2, least(attempts, 7))) * interval '1 millisecond') where id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (error == null) {
                ps.setObject(1, id);
            } else {
                ps.setString(1, error.length() > 1000 ? error.substring(0, 1000) : error);
                ps.setObject(2, id);
            }
            ps.executeUpdate();
        }
    }

    private void housekeeping() {
        long now = System.currentTimeMillis();
        if (now - lastHousekeeping < 5000) {
            return;
        }
        lastHousekeeping = now;
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                try (Connection c = dataSource.getConnection()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "select count(*), coalesce(extract(epoch from (now() - min(created_at))) * 1000, 0) "
                                    + "from outbox_event where published_at is null");
                            ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            pending.set(rs.getLong(1));
                            oldestAgeMs.set((long) rs.getDouble(2));
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "delete from outbox_event where published_at < now() - interval '24 hours'")) {
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "delete from inbox_event where received_at < now() - interval '7 days'")) {
                        ps.executeUpdate();
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (Exception e) {
            LOG.debugf("Outbox housekeeping failed: %s", e.getMessage());
        }
    }

    public long pending() {
        return pending.get();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
