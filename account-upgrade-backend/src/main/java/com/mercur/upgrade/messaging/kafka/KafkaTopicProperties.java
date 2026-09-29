package com.mercur.upgrade.messaging.kafka;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Kafka-specific settings ({@code upgrade.messaging.kafka}); the topic's partition count is
 * {@code upgrade.messaging.partitions}.
 *
 * <p>Partitions and consumer threads are separate on purpose: Kafka gives each partition to one consumer
 * in the group, so with N instances the topic needs at least N x {@code consumerConcurrency} partitions
 * for every thread to get work.
 *
 * @param replicationFactor   copies of each partition; production needs 3 (see PRODUCTION_GUARDRAILS.md)
 * @param minInsyncReplicas   copies that must acknowledge a write ({@code acks=all}); production needs 2
 * @param consumerConcurrency consumer threads per instance
 */
@Validated
@ConfigurationProperties("upgrade.messaging.kafka")
public record KafkaTopicProperties(
        @DefaultValue("1") @Min(1) int replicationFactor,
        @DefaultValue("1") @Min(1) int minInsyncReplicas,
        @DefaultValue("4") @Min(1) int consumerConcurrency) {
}
