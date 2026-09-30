package com.mercur.upgrade.notification.jpa;

import com.mercur.upgrade.notification.EmailMessage;
import com.mercur.upgrade.notification.RecipientRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A row of {@code notification_outbox}: an email waiting for delivery, or delivered.
 *
 * <p>Ids come from the column's own sequence, 50 at a time (its increment, set by the account-update-db-schema
 * changeset 004), so Hibernate can batch the inserts of many emails into few statements.
 */
@Entity
@Table(name = "notification_outbox")
public class OutboxEmailEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "notification_outbox_id")
    @SequenceGenerator(name = "notification_outbox_id", sequenceName = "notification_outbox_id_seq",
            allocationSize = 50)
    @Column(name = "id")
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private RecipientRole role;

    @Column(name = "recipient", nullable = false)
    private String recipient;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEmailEntity() {
    }

    /** A new email, due for delivery when it was created. */
    public static OutboxEmailEntity pending(EmailMessage message) {
        OutboxEmailEntity row = new OutboxEmailEntity();
        row.eventId = message.eventId();
        row.role = message.role();
        row.recipient = message.recipient();
        row.subject = message.subject();
        row.body = message.body();
        row.createdAt = message.createdAt();
        row.nextAttemptAt = message.createdAt();
        return row;
    }

    public EmailMessage toMessage() {
        return new EmailMessage(eventId, role, recipient, subject, body, createdAt);
    }

    /** A failed delivery attempt: counted, with its error, and due again at {@code nextAttempt}. */
    public void recordFailure(String error, Instant nextAttempt) {
        attempts++;
        lastError = error;
        nextAttemptAt = nextAttempt;
    }

    public Long getId() {
        return id;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
