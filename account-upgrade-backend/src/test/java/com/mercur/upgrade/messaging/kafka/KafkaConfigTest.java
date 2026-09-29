package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.messaging.MessagingProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    private final KafkaConfig config = new KafkaConfig();
    private final MessagingProperties messaging = new MessagingProperties(12,
            new MessagingProperties.Topics("qa.upgrade-requests", "qa.upgrade-requests-dlq"));
    private final KafkaTopicProperties durable = new KafkaTopicProperties(3, 2, 3);

    @Test
    void mainTopicUsesConfiguredNamePartitionsReplicationAndMinInsyncReplicas() {
        NewTopic topic = config.upgradeRequestsTopic(messaging, durable);

        assertThat(topic.name()).isEqualTo("qa.upgrade-requests");
        assertThat(topic.numPartitions()).isEqualTo(12);
        assertThat(topic.replicationFactor()).isEqualTo((short) 3);
        assertThat(topic.configs()).containsExactlyEntriesOf(Map.of("min.insync.replicas", "2"));
    }

    @Test
    void deadLetterQueueUsesConfiguredNameAndMirrorsTheMainTopicSoRecordsKeepTheirPartition() {
        NewTopic main = config.upgradeRequestsTopic(messaging, durable);
        NewTopic dlq = config.upgradeRequestsDeadLetterQueue(messaging, durable);

        assertThat(dlq.name()).isEqualTo("qa.upgrade-requests-dlq");
        assertThat(dlq.numPartitions()).isEqualTo(main.numPartitions());
        assertThat(dlq.replicationFactor()).isEqualTo(main.replicationFactor());
        assertThat(dlq.configs()).isEqualTo(main.configs());
    }

    @Test
    void failedRecordsAreRoutedToTheConfiguredDeadLetterQueueOnTheirOriginalPartition() {
        ConsumerRecord<String, String> failed = new ConsumerRecord<>("qa.upgrade-requests", 7, 42L, "u1", "{}");

        TopicPartition destination = KafkaConfig.deadLetterDestination("qa.upgrade-requests-dlq")
                .apply(failed, new IllegalStateException("boom"));

        assertThat(destination).isEqualTo(new TopicPartition("qa.upgrade-requests-dlq", 7));
    }
}
