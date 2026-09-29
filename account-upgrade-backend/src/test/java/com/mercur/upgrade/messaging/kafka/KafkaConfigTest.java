package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.messaging.MessagingProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    private final KafkaConfig config = new KafkaConfig();
    private final MessagingProperties messaging = new MessagingProperties(12);
    private final KafkaTopicProperties durable = new KafkaTopicProperties(3, 2, 3);

    @Test
    void mainTopicUsesConfiguredPartitionsReplicationAndMinInsyncReplicas() {
        NewTopic topic = config.upgradeRequestsTopic(messaging, durable);

        assertThat(topic.name()).isEqualTo("upgrade-requests");
        assertThat(topic.numPartitions()).isEqualTo(12);
        assertThat(topic.replicationFactor()).isEqualTo((short) 3);
        assertThat(topic.configs()).containsExactlyEntriesOf(Map.of("min.insync.replicas", "2"));
    }

    @Test
    void deadLetterTopicMirrorsTheMainTopicSoRecordsKeepTheirPartition() {
        NewTopic main = config.upgradeRequestsTopic(messaging, durable);
        NewTopic dlt = config.upgradeRequestsDeadLetterTopic(messaging, durable);

        assertThat(dlt.name()).isEqualTo("upgrade-requests-dlt");
        assertThat(dlt.numPartitions()).isEqualTo(main.numPartitions());
        assertThat(dlt.replicationFactor()).isEqualTo(main.replicationFactor());
        assertThat(dlt.configs()).isEqualTo(main.configs());
    }
}
