package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.common.Topics;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.UpgradeRequestHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Consumes the {@code upgrade-requests} topic from Kafka. */
@Component
public class KafkaUpgradeRequestListener {

    private final UpgradeRequestHandler handler;
    private final JsonMapper jsonMapper;

    public KafkaUpgradeRequestListener(UpgradeRequestHandler handler, JsonMapper jsonMapper) {
        this.handler = handler;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = Topics.UPGRADE_REQUESTS, concurrency = "${upgrade.messaging.kafka.consumer-concurrency:4}")
    public void onMessage(String payload) {
        handler.handle(jsonMapper.readValue(payload, UpgradeRequestedEvent.class));
    }
}
