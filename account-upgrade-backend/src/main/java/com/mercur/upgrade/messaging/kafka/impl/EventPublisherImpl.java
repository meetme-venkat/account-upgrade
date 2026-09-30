package com.mercur.upgrade.messaging.kafka.impl;

import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.EventPublisher;
import com.mercur.upgrade.messaging.MessagingProperties;
import com.mercur.upgrade.messaging.PublishException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Publishes upgrade events to Kafka. */
@Component
public class EventPublisherImpl implements EventPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final String topic;

    public EventPublisherImpl(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper,
                              MessagingProperties messaging) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.topic = messaging.topics().upgradeRequests();
    }

    @Override
    public void publish(UpgradeRequestedEvent event) {
        String payload = jsonMapper.writeValueAsString(event);
        try {
            // Wait for the broker ack so a 202 response means the event is durably stored.
            kafkaTemplate.send(topic, event.key(), payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrupted while publishing event " + event.eventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublishException("Kafka did not acknowledge event " + event.eventId(), e);
        }
    }

    /**
     * Hands every event to the producer first, then waits for the acknowledgements. The producer batches and pipelines
     * them, so a batch costs a few broker round trips instead of one per event. Per key (partition) order is kept: the
     * producer is idempotent.
     */
    @Override
    public Map<String, PublishException> publishAll(List<UpgradeRequestedEvent> events) {
        Map<String, CompletableFuture<?>> sends = new LinkedHashMap<>();
        Map<String, PublishException> failures = new LinkedHashMap<>();
        for (UpgradeRequestedEvent event : events) {
            try {
                sends.put(event.eventId(), kafkaTemplate.send(topic, event.key(), jsonMapper.writeValueAsString(event)));
            } catch (RuntimeException e) {
                // The producer could not take it (e.g. no metadata or a full buffer within max.block.ms).
                failures.put(event.eventId(), new PublishException("Kafka did not accept event " + event.eventId(), e));
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SEND_TIMEOUT_SECONDS);
        boolean interrupted = false;
        for (Map.Entry<String, CompletableFuture<?>> send : sends.entrySet()) {
            String eventId = send.getKey();
            if (interrupted) {
                failures.put(eventId, new PublishException("Interrupted while publishing event " + eventId));
                continue;
            }
            try {
                send.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
                failures.put(eventId, new PublishException("Interrupted while publishing event " + eventId, e));
            } catch (ExecutionException | TimeoutException e) {
                failures.put(eventId, new PublishException("Kafka did not acknowledge event " + eventId, e));
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return failures;
    }
}
