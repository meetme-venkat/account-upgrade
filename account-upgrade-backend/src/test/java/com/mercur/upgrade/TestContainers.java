package com.mercur.upgrade;

import org.apache.kafka.common.Uuid;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Container images and shared instances for integration tests. Containers are started once per test JVM
 * and reused by every test class (Testcontainers' Ryuk removes them when the JVM exits).
 */
public final class TestContainers {

    public static final String POSTGRES_IMAGE = "postgres:16";
    public static final String KAFKA_IMAGE = "apache/kafka:3.9.1";

    private static PostgreSQLContainer postgres;
    private static KafkaContainer kafka;
    private static KafkaCluster kafkaCluster;

    private TestContainers() {
    }

    public static synchronized PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(POSTGRES_IMAGE).withDatabaseName("account_upgrade_test");
            postgres.start();
        }
        return postgres;
    }

    /** A single broker: replication factor 1, as in local development. */
    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer(KAFKA_IMAGE);
            kafka.start();
        }
        return kafka;
    }

    /** Three brokers, so topics can have replication factor 3 and min.insync.replicas 2 as in production. */
    public static synchronized KafkaCluster kafkaCluster() {
        if (kafkaCluster == null) {
            kafkaCluster = new KafkaCluster(3);
            kafkaCluster.start();
        }
        return kafkaCluster;
    }

    /** A KRaft cluster of combined broker/controller nodes on a private Docker network. */
    public static final class KafkaCluster {

        private final List<KafkaContainer> brokers;

        KafkaCluster(int size) {
            Network network = Network.newNetwork();
            String clusterId = Uuid.randomUuid().toString();
            String voters = IntStream.range(0, size)
                    .mapToObj(i -> i + "@broker-" + i + ":9094")
                    .collect(Collectors.joining(","));
            String internalRf = Integer.toString(size);
            this.brokers = IntStream.range(0, size)
                    .mapToObj(i -> new KafkaContainer(KAFKA_IMAGE)
                            .withNetwork(network)
                            .withNetworkAliases("broker-" + i)
                            // The container advertises its hostname to the other brokers: make it the alias.
                            .withCreateContainerCmdModifier(cmd -> cmd.withHostName("broker-" + i))
                            .withEnv("CLUSTER_ID", clusterId)
                            .withEnv("KAFKA_NODE_ID", Integer.toString(i))
                            .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", voters)
                            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", internalRf)
                            .withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", internalRf)
                            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", internalRf)
                            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "2")
                            .withStartupTimeout(Duration.ofMinutes(2)))
                    .toList();
        }

        void start() {
            // In parallel: each node only reports "started" once the controller quorum has formed.
            brokers.parallelStream().forEach(GenericContainer::start);
        }

        public String bootstrapServers() {
            return brokers.stream().map(KafkaContainer::getBootstrapServers).collect(Collectors.joining(","));
        }
    }
}
