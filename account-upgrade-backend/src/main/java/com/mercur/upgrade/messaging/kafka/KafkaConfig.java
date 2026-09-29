package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.messaging.MessagingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

import java.util.function.BiFunction;

/**
 * Topic declarations and retry/dead-letter policy.
 *
 * <p>Both topics are declared with the configured replication factor and {@code min.insync.replicas}.
 * Note that Kafka never changes the replication factor of an existing topic: a topic first created with
 * RF 1 must be fixed by hand (or by infrastructure-as-code), not by redeploying with new settings.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    static final int RETRIES = 10;

    @Bean
    NewTopic upgradeRequestsTopic(MessagingProperties messaging, KafkaTopicProperties kafka) {
        return topic(messaging.topics().upgradeRequests(), messaging, kafka);
    }

    /** Same partition count as the main topic: a dead letter keeps its original partition. */
    @Bean
    NewTopic upgradeRequestsDeadLetterQueue(MessagingProperties messaging, KafkaTopicProperties kafka) {
        return topic(messaging.topics().upgradeRequestsDlq(), messaging, kafka);
    }

    /**
     * Retries a failed record with exponential backoff (1 s doubling to 30 s, ~3.5 min in total), so events
     * survive a short database outage, then publishes it to the dead-letter queue
     * ({@code upgrade.messaging.topics.upgrade-requests-dlq}). Unreadable payloads cannot succeed on retry and
     * go to the dead-letter queue immediately.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MessagingProperties messaging,
                                          MeterRegistry meterRegistry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(RETRIES);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000);
        String deadLetterQueue = messaging.topics().upgradeRequestsDlq();
        Counter deadLetters = Counter.builder("upgrade.dead-letters")
                .description("Events moved to " + deadLetterQueue + " by this instance")
                .register(meterRegistry);
        DeadLetterPublishingRecoverer publisher =
                new DeadLetterPublishingRecoverer(kafkaTemplate, deadLetterDestination(deadLetterQueue));
        ConsumerRecordRecoverer countingRecoverer = (record, exception) -> {
            publisher.accept(record, exception);
            deadLetters.increment();
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(countingRecoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }

    /**
     * The configured dead-letter queue, same partition as the failed record. Explicit rather than the recoverer's
     * default ({@code <topic>-dlt}), so the destination is always the queue declared above.
     */
    static BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> deadLetterDestination(String deadLetterQueue) {
        return (record, exception) -> new TopicPartition(deadLetterQueue, record.partition());
    }

    private static NewTopic topic(String name, MessagingProperties messaging, KafkaTopicProperties kafka) {
        return TopicBuilder.name(name)
                .partitions(messaging.partitions())
                .replicas(kafka.replicationFactor())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(kafka.minInsyncReplicas()))
                .build();
    }
}
