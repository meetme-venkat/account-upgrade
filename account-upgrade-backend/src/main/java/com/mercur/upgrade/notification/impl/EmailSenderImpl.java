package com.mercur.upgrade.notification.impl;

import com.mercur.upgrade.notification.EmailMessage;
import com.mercur.upgrade.notification.EmailSender;
import com.mercur.upgrade.notification.OutboxRelay;
import com.mercur.upgrade.notification.jpa.OutboxEmailEntity;
import com.mercur.upgrade.notification.jpa.OutboxEmailJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Transactional outbox writer: instead of sending, it records the email in {@code notification_outbox} within the
 * caller's transaction, so the email exists if and only if the decision is committed. {@link OutboxRelay} delivers
 * it afterwards.
 *
 * <p>An email is recorded once per event and recipient role: the processors write emails only for the decisions
 * their own transaction stored, so a second one for the same event and role is a bug, and fails the transaction on
 * the unique key {@code notification_outbox_event_role}. Hibernate batches the inserts (ids reserved 50 at a time).
 */
@Component
public class EmailSenderImpl implements EmailSender {

    private final OutboxEmailJpaRepository outbox;

    public EmailSenderImpl(OutboxEmailJpaRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public void send(EmailMessage message) {
        outbox.save(OutboxEmailEntity.pending(message));
    }

    @Override
    public void sendAll(List<EmailMessage> messages) {
        outbox.saveAll(messages.stream().map(OutboxEmailEntity::pending).toList());
    }
}
