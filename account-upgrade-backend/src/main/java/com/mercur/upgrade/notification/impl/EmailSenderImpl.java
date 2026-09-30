package com.mercur.upgrade.notification.impl;

import com.mercur.upgrade.notification.EmailMessage;
import com.mercur.upgrade.notification.EmailSender;
import com.mercur.upgrade.notification.OutboxRelay;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Messages per multi-row insert: well below PostgreSQL's 65,535 bind parameters (6 per message). */
    static final int MAX_ROWS_PER_STATEMENT = 1000;

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

    /** The same insert for many messages: one multi-row statement per {@value #MAX_ROWS_PER_STATEMENT} messages. */
    @Override
    public void sendAll(List<EmailMessage> messages) {
        for (int from = 0; from < messages.size(); from += MAX_ROWS_PER_STATEMENT) {
            List<EmailMessage> rows = messages.subList(from, Math.min(from + MAX_ROWS_PER_STATEMENT, messages.size()));
            StringBuilder sql = new StringBuilder("""
                    INSERT INTO notification_outbox
                        (event_id, role, recipient, subject, body, created_at, next_attempt_at)
                    VALUES """);
            Map<String, Object> params = new HashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                EmailMessage message = rows.get(i);
                sql.append(i == 0 ? " " : ", ")
                        .append(("(:eventId%1$d, :role%1$d, :recipient%1$d, :subject%1$d, :body%1$d, "
                                + ":createdAt%1$d, :createdAt%1$d)").formatted(i));
                params.put("eventId" + i, message.eventId());
                params.put("role" + i, message.role().name());
                params.put("recipient" + i, message.recipient());
                params.put("subject" + i, message.subject());
                params.put("body" + i, message.body());
                params.put("createdAt" + i, OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC));
            }
            sql.append(" ON CONFLICT (event_id, role) DO NOTHING");
            jdbc.sql(sql.toString()).params(params).update();
        }
    }
}
