package com.mercur.upgrade.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/** Recently delivered emails from the outbox, shared by all instances. */
@Component
public class JdbcNotificationLog implements NotificationLog {

    private final JdbcClient jdbc;
    private final int capacity;

    public JdbcNotificationLog(JdbcClient jdbc, @Value("${upgrade.notification.outbox-capacity:1000}") int capacity) {
        this.jdbc = jdbc;
        this.capacity = capacity;
    }

    @Override
    public List<EmailMessage> sentMessages() {
        return jdbc.sql("""
                        SELECT * FROM (
                            SELECT id, event_id, role, recipient, subject, body, created_at
                            FROM notification_outbox
                            WHERE sent_at IS NOT NULL
                            ORDER BY id DESC
                            LIMIT :capacity) newest
                        ORDER BY id""")
                .param("capacity", capacity)
                .query((rs, rowNum) -> new EmailMessage(
                        rs.getString("event_id"),
                        RecipientRole.valueOf(rs.getString("role")),
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
