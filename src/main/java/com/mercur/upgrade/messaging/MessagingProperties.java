package com.mercur.upgrade.messaging;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Broker settings shared by the in-memory and Kafka adapters.
 *
 * @param partitions    number of partitions, which is also the number of parallel consumers
 * @param queueCapacity per-partition capacity of the in-memory broker (back-pressure limit)
 * @param maxAttempts   delivery attempts before an event is moved to the dead-letter store/topic
 * @param retryBackoff  pause between delivery attempts
 * @param deadLetterCapacity number of most recent dead letters the in-memory broker retains
 */
@Validated
@ConfigurationProperties("upgrade.messaging")
public record MessagingProperties(
        @DefaultValue("4") @Min(1) int partitions,
        @DefaultValue("10000") @Min(1) int queueCapacity,
        @DefaultValue("3") @Min(1) int maxAttempts,
        @DefaultValue("200ms") Duration retryBackoff,
        @DefaultValue("1000") @Min(1) int deadLetterCapacity) {
}
