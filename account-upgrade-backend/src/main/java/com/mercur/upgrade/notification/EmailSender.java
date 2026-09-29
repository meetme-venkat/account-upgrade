package com.mercur.upgrade.notification;

/** Delivery channel abstraction; swap the mock for an SMTP/SES adapter in production. */
public interface EmailSender {

    /**
     * @throws RuntimeException if the message could not be delivered
     */
    void send(EmailMessage message);
}
