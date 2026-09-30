package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.UpgradeRequestHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumes the upgrade-requests topic ({@code upgrade.messaging.topics.upgrade-requests}) from Kafka, a poll's
 * records at a time ({@code spring.kafka.consumer.max-poll-records}).
 *
 * <p>Each batch is processed in one transaction ({@link UpgradeRequestHandler#handleAll}). If that fails, the batch
 * is processed again one event at a time, and the first event that still fails is reported to the error handler
 * ({@link KafkaConfig#kafkaErrorHandler}) as a {@link BatchListenerFailedException}. That event is retried and, when
 * the retries are exhausted, dead-lettered on its own. The events before it are committed, and the ones after it
 * are delivered again. An unreadable record is reported the same way, after the readable records before it are
 * processed; it is not retried.
 */
@Component
public class KafkaUpgradeRequestListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaUpgradeRequestListener.class);

    private final UpgradeRequestHandler handler;
    private final JsonMapper jsonMapper;

    public KafkaUpgradeRequestListener(UpgradeRequestHandler handler, JsonMapper jsonMapper) {
        this.handler = handler;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "${upgrade.messaging.topics.upgrade-requests}",
            concurrency = "${upgrade.messaging.kafka.consumer-concurrency:4}", batch = "true")
    public void onMessages(List<ConsumerRecord<String, String>> records) {
        List<UpgradeRequestedEvent> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                events.add(jsonMapper.readValue(record.value(), UpgradeRequestedEvent.class));
            } catch (JacksonException e) {
                process(records, events);
                throw new BatchListenerFailedException("Unreadable event at " + position(record), e, record);
            }
        }
        process(records, events);
    }

    /** The events, read from the first {@code events.size()} records, in order. */
    private void process(List<ConsumerRecord<String, String>> records, List<UpgradeRequestedEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        try {
            handler.handleAll(events);
        } catch (RuntimeException batchFailure) {
            log.warn("A batch of {} events failed ({}); processing them one at a time", events.size(),
                    batchFailure.toString());
            for (int i = 0; i < events.size(); i++) {
                try {
                    handler.handle(events.get(i));
                } catch (RuntimeException e) {
                    throw new BatchListenerFailedException(
                            "Event " + events.get(i).eventId() + " at " + position(records.get(i)) + " failed", e,
                            records.get(i));
                }
            }
        }
    }

    private static String position(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }
}
