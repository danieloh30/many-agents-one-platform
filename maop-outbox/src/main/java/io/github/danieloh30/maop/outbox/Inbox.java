package io.github.danieloh30.maop.outbox;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Durable de-duplication: records (consumer, event id) in the same transaction as the state change. */
@ApplicationScoped
public class Inbox {

    @Inject
    AgroalDataSource dataSource;

    /** @return true the first time this consumer sees the event; false for a duplicate delivery. */
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean firstDelivery(String consumer, String eventId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "insert into inbox_event (consumer, event_id) values (?, ?) on conflict do nothing")) {
            ps.setString(1, consumer);
            ps.setString(2, eventId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Inbox insert failed", e);
        }
    }
}
