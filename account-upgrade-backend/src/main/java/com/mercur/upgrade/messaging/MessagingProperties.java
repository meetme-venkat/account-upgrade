package com.mercur.upgrade.messaging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Broker settings ({@code upgrade.messaging}); Kafka-specific ones are in {@code KafkaTopicProperties}.
 *
 * @param partitions partitions of the upgrade-requests topic (and of its dead-letter queue)
 * @param topics     topic names, which differ per environment
 */
@Validated
@ConfigurationProperties("upgrade.messaging")
public record MessagingProperties(
        @DefaultValue("4") @Min(1) int partitions,
        @Valid @NotNull Topics topics) {

    /** Legal Kafka topic names. */
    private static final String TOPIC_NAME = "[a-zA-Z0-9._-]{1,249}";

    /**
     * Topic names ({@code upgrade.messaging.topics}). Defaults are in {@code application.yml}; each environment
     * overrides them, e.g. with {@code UPGRADE_MESSAGING_TOPICS_UPGRADE_REQUESTS}. The dead-letter queue defaults
     * to the main topic's name plus {@code -dlq}, so it follows a renamed main topic.
     *
     * @param upgradeRequests    upgrade events from both ingestion channels
     * @param upgradeRequestsDlq dead-letter queue: events that failed all retries, or could not be read
     */
    public record Topics(
            @NotBlank @Pattern(regexp = TOPIC_NAME) String upgradeRequests,
            @NotBlank @Pattern(regexp = TOPIC_NAME) String upgradeRequestsDlq) {

        /** A dead-letter queue that is the main topic would redeliver failed events forever. */
        @AssertTrue(message = "upgrade-requests-dlq must be a different topic from upgrade-requests")
        public boolean isDeadLetterQueueSeparate() {
            return upgradeRequestsDlq == null || !upgradeRequestsDlq.equals(upgradeRequests);
        }
    }
}
