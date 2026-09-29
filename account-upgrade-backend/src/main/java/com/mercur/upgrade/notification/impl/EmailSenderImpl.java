package com.mercur.upgrade.notification.impl;

import com.mercur.upgrade.notification.EmailMessage;
import com.mercur.upgrade.notification.EmailSender;
import com.mercur.upgrade.notification.OutboxRelay;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Transactional outbox writer: instead of sending, it records the email in
 * {@code notification_outbox} within the caller's transaction, so the email exists if and only if the
 * decision is committed. {@link OutboxRelay} delivers it afterwards.
 *
 * <p>{@code ON CONFLICT DO NOTHING}: when two instances process the same event, the second insert waits
 * for the first transaction and then inserts nothing, instead of failing with a unique violation.
 */
@Component
public class EmailSenderImpl implements EmailSender {

    private final JdbcClient jdbc;

    public EmailSenderImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void send(EmailMessage message) {
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO notification_outbox
                            (event_id, role, recipient, subject, body, created_at, next_attempt_at)
                        VALUES (:eventId, :role, :recipient, :subject, :body, :createdAt, :createdAt)
                        ON CONFLICT (event_id, role) DO NOTHING""")
                .param("eventId", message.eventId())
                .param("role", message.role().name())
                .param("recipient", message.recipient())
                .param("subject", message.subject())
                .param("body", message.body())
                .param("createdAt", createdAt)
                .update();
    }
}
