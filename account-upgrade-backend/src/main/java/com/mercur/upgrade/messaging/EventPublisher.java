package com.mercur.upgrade.messaging;

import com.mercur.upgrade.common.UpgradeRequestedEvent;

import java.util.List;
import java.util.Map;

/**
 * Producer side of the {@code upgrade-requests} topic, implemented by
 * {@link com.mercur.upgrade.messaging.kafka.impl.EventPublisherImpl}.
 */
public interface EventPublisher {

    /**
     * Publishes the event. Returns once the broker has durably accepted it.
     *
     * @throws PublishException if the broker cannot accept the event (unreachable or not acknowledging)
     */
    void publish(UpgradeRequestedEvent event);

    /**
     * Publishes all the events at once, without waiting for each one's acknowledgement before sending the next, and
     * returns once every event has been durably accepted or has failed. Events with the same key keep their order.
     *
     * @return the events that could not be published, by event id, with the reason; empty when all were accepted
     */
    Map<String, PublishException> publishAll(List<UpgradeRequestedEvent> events);
}
