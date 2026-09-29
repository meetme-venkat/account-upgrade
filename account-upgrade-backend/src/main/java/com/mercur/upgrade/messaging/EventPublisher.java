package com.mercur.upgrade.messaging;

import com.mercur.upgrade.common.UpgradeRequestedEvent;

/**
 * Producer side of the {@code upgrade-requests} topic, implemented by
 * {@link com.mercur.upgrade.messaging.kafka.KafkaEventPublisher}.
 */
public interface EventPublisher {

    /**
     * Publishes the event. Returns once the broker has durably accepted it.
     *
     * @throws PublishException if the broker cannot accept the event (unreachable or not acknowledging)
     */
    void publish(UpgradeRequestedEvent event);
}
