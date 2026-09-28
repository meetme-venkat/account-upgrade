package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.common.Topics;
import com.mercur.upgrade.messaging.MessagingProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Topic declaration and retry/dead-letter policy for the "kafka" profile. */
@Configuration(proxyBeanMethods = false)
@Profile("kafka")
public class KafkaConfig {

    @Bean
    NewTopic upgradeRequestsTopic(MessagingProperties properties) {
        return TopicBuilder.name(Topics.UPGRADE_REQUESTS).partitions(properties.partitions()).replicas(1).build();
    }

    /** Retries failed records, then publishes them to {@code upgrade-requests-dlt}. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MessagingProperties properties) {
        FixedBackOff backOff = new FixedBackOff(properties.retryBackoff().toMillis(), properties.maxAttempts() - 1L);
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), backOff);
    }
}
