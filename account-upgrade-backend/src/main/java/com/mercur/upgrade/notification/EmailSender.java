package com.mercur.upgrade.notification;

import java.util.List;

/** Delivery channel abstraction; swap the mock for an SMTP/SES adapter in production. */
public interface EmailSender {

    /**
     * @throws RuntimeException if the message could not be delivered
     */
    void send(EmailMessage message);

    /**
     * Sends several messages. By default one {@link #send} each; an implementation can do it at once (the outbox:
     * one insert).
     *
     * @throws RuntimeException if a message could not be delivered; which of the others were is unspecified
     */
    default void sendAll(List<EmailMessage> messages) {
        messages.forEach(this::send);
    }
}
