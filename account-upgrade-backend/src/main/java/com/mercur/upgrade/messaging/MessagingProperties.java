package com.mercur.upgrade.messaging;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Broker settings ({@code upgrade.messaging}); Kafka-specific ones are in {@code KafkaTopicProperties}.
 *
 * @param partitions partitions of the {@code upgrade-requests} topic (and its dead-letter topic)
 */
@Validated
@ConfigurationProperties("upgrade.messaging")
public record MessagingProperties(@DefaultValue("4") @Min(1) int partitions) {
}
