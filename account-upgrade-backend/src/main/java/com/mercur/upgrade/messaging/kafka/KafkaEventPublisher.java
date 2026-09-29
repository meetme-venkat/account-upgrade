package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.common.Topics;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.EventPublisher;
import com.mercur.upgrade.messaging.PublishException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Publishes upgrade events to Kafka. */
@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void publish(UpgradeRequestedEvent event) {
        String payload = jsonMapper.writeValueAsString(event);
        try {
            // Wait for the broker ack so a 202 response means the event is durably stored.
            kafkaTemplate.send(Topics.UPGRADE_REQUESTS, event.key(), payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrupted while publishing event " + event.eventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublishException("Kafka did not acknowledge event " + event.eventId(), e);
        }
    }
}
