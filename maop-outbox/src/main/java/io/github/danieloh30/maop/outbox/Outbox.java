package io.github.danieloh30.maop.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tracing.Tracing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Writes outbox rows inside the caller's transaction ({@code MANDATORY}): the row commits if and only if the
 * business change commits. The current trace context is stamped into events so the async hop stays connected.
 */
@ApplicationScoped
public class Outbox {

    public static final String KIND_EVENT = "EVENT";

    @Inject
    AgroalDataSource dataSource;

    @Transactional(Transactional.TxType.MANDATORY)
    public void event(String subject, CloudEvent event) {
        CloudEvent stamped = Tracing.stampCurrent(event);
        insert(UUID.fromString(stamped.id()), KIND_EVENT, subject, CloudEventCodec.toStructured(stamped));
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void row(UUID id, String kind, String subject, JsonNode payload) {
        insert(id, kind, subject, payload);
    }

    private void insert(UUID id, String kind, String subject, JsonNode payload) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "insert into outbox_event (id, kind, subject, payload) values (?, ?, ?, ?::jsonb)")) {
            ps.setObject(1, id);
            ps.setString(2, kind);
            ps.setString(3, subject);
            ps.setString(4, Json.write(payload));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Outbox insert failed", e);
        }
    }
}
