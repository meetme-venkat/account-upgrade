package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.IntegrationTestSupport;
import com.mercur.upgrade.TestContainers;
import com.mercur.upgrade.messaging.MessagingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Topic names come from configuration: with the main topic renamed for an environment, the application
 * consumes that topic and its dead-letter queue follows it ({@code <topic>-dlq}). A payload that can never be
 * read is not retried and lands in that queue on the real broker.
 */
@TestPropertySource(properties = {
        "upgrade.messaging.topics.upgrade-requests=it.env-a.upgrade-requests",
        "spring.kafka.consumer.group-id=it-dead-letter-queue"})
class DeadLetterQueueIntegrationTest extends IntegrationTestSupport {

    private static final String TOPIC = "it.env-a.upgrade-requests";
    private static final String DEAD_LETTER_QUEUE = "it.env-a.upgrade-requests-dlq";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private MessagingProperties messaging;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void theDeadLetterQueueFollowsTheEnvironmentsTopicName() {
        assertThat(messaging.topics())
                .isEqualTo(new MessagingProperties.Topics(TOPIC, DEAD_LETTER_QUEUE));
    }

    @Test
    void anUnreadableEventGoesStraightToTheConfiguredDeadLetterQueue() throws Exception {
        String payload = "not json " + UUID.randomUUID();

        kafkaTemplate.send(TOPIC, "it-dlq-user", payload).get(10, TimeUnit.SECONDS);

        List<ConsumerRecord<String, String>> deadLetters = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-dlq-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(DEAD_LETTER_QUEUE));
            // Generous: the listener first has to join its consumer group.
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(deadLetters::add);
                assertThat(deadLetters).extracting(ConsumerRecord::value).contains(payload);
            });
        }

        ConsumerRecord<String, String> deadLetter = deadLetters.stream()
                .filter(r -> r.value().equals(payload)).findFirst().orElseThrow();
        assertThat(deadLetter.key()).isEqualTo("it-dlq-user");
        assertThat(meterRegistry.get("upgrade.dead-letters").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(count("processed_upgrades")).isZero();
    }
}
