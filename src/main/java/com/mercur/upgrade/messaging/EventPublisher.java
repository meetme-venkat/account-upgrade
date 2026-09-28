package com.mercur.upgrade.messaging;

import com.mercur.upgrade.common.UpgradeRequestedEvent;

/**
 * Producer side of the {@code upgrade-requests} topic.
 *
 * <p>Implementations: {@link com.mercur.upgrade.messaging.inmemory.InMemoryMessageBroker} (default)
 * and {@link com.mercur.upgrade.messaging.kafka.KafkaEventPublisher} ("kafka" profile).
 */
public interface EventPublisher {

    /**
     * Publishes the event. Returns once the broker has accepted it.
     *
     * @throws PublishException if the broker cannot accept the event (full, stopped or unreachable)
     */
    void publish(UpgradeRequestedEvent event);
}
