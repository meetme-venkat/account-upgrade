package com.mercur.upgrade.notification;

/**
 * The actual delivery channel used by {@link OutboxRelay} (SMTP, SES, ...).
 *
 * <p>Delivery from the outbox is at-least-once: if an instance crashes after delivering but before
 * marking the row sent, the message is delivered again. Channels that support it should pass
 * {@code idempotencyKey} on so the provider or recipient can drop the duplicate.
 */
public interface EmailChannel {

    /**
     * @param idempotencyKey stable per notification: {@code <eventId>:<role>}
     * @throws RuntimeException if the message could not be delivered; the relay retries with backoff
     */
    void deliver(EmailMessage message, String idempotencyKey);
}
