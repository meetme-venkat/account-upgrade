package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.common.Topics;
import com.mercur.upgrade.messaging.MessagingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
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
        return topic(Topics.UPGRADE_REQUESTS, messaging, kafka);
    }

    /** Same partition count as the main topic: the recoverer publishes to the record's original partition. */
    @Bean
    NewTopic upgradeRequestsDeadLetterTopic(MessagingProperties messaging, KafkaTopicProperties kafka) {
        return topic(Topics.UPGRADE_REQUESTS_DLT, messaging, kafka);
    }

    /**
     * Retries a failed record with exponential backoff (1 s doubling to 30 s, ~3.5 min in total), so events
     * survive a short database outage, then publishes it to {@code upgrade-requests-dlt}. Unreadable payloads
     * cannot succeed on retry and go to the dead-letter topic immediately.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(RETRIES);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000);
        Counter deadLetters = Counter.builder("upgrade.dead-letters")
                .description("Events moved to " + Topics.UPGRADE_REQUESTS_DLT + " by this instance")
                .register(meterRegistry);
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafkaTemplate);
        ConsumerRecordRecoverer countingRecoverer = (record, exception) -> {
            publisher.accept(record, exception);
            deadLetters.increment();
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(countingRecoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }

    private static NewTopic topic(String name, MessagingProperties messaging, KafkaTopicProperties kafka) {
        return TopicBuilder.name(name)
                .partitions(messaging.partitions())
                .replicas(kafka.replicationFactor())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(kafka.minInsyncReplicas()))
                .build();
    }
}
