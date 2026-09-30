package com.mercur.upgrade.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Kubernetes deployment itself, read-only through kubectl: workloads ready, schema applied, Kafka healthy.
 * Leave out with -DexcludedGroups=infrastructure, e.g. for a deployment without kubectl access.
 */
@Order(1)
@Tag("infrastructure")
@DisplayName("Infrastructure")
class InfrastructureTest {

    @ParameterizedTest(name = "{0} {1}: {2} pods, all ready")
    @CsvSource({
            "statefulset, postgres, 1",
            "statefulset, kafka, 3",
            "deployment, account-upgrade-backend, 2",
            "deployment, account-upgrade-frontend, 2"})
    void workload_is_fully_ready(String kind, String name, int replicas) {
        String readiness = Kubectl.run("get", kind, name, "-o", "jsonpath={.status.readyReplicas}/{.spec.replicas}");
        assertThat(readiness).isEqualTo(replicas + "/" + replicas);
    }

    @Test
    void schema_job_succeeded() {
        assertThat(Kubectl.run("get", "job", "account-update-db-schema", "-o", "jsonpath={.status.succeeded}"))
                .isEqualTo("1");
    }

    @Test
    void schema_changelog_applied() {
        assertThat(Integer.parseInt(Kubectl.sql("select count(*) from databasechangelog"))).isPositive();
        assertThat(Kubectl.sql(
                "select count(*) from databasechangelog where exectype not in ('EXECUTED', 'MARK_RAN')"))
                .isEqualTo("0");
    }

    @Test
    void application_tables_exist() {
        assertThat(Kubectl.sql("select count(*) from pg_tables where schemaname = 'public'"
                + " and tablename in ('processed_upgrades', 'notification_outbox')")).isEqualTo("2");
    }

    @ParameterizedTest(name = "topic {0} has replication factor 3")
    @CsvSource({"''", "-dlq"})
    void topics_are_replicated(String suffix) {
        String topic = E2eSettings.TOPIC + suffix;
        String description = Kubectl.kafka("kafka-topics.sh", "--describe", "--topic", topic);
        assertThat(description).containsPattern("(?m)^Topic: " + topic + "\\s.*ReplicationFactor: 3\\b");
    }

    @Test
    void dead_letter_queue_is_empty() {
        // One line per partition: topic:partition:offset.
        long deadLetters = Kubectl.kafka("kafka-get-offsets.sh", "--topic", E2eSettings.TOPIC + "-dlq")
                .lines().filter(line -> !line.isBlank())
                .mapToLong(line -> Long.parseLong(line.split(":")[2].strip()))
                .sum();
        assertThat(deadLetters).isZero();
    }

    @Test
    void consumer_group_has_no_lag() {
        // Columns: GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG ...
        long lag = Kubectl.kafka("kafka-consumer-groups.sh", "--describe", "--group", "upgrade-eligibility")
                .lines()
                .map(line -> line.strip().split("\\s+"))
                .filter(columns -> columns.length > 5 && columns[1].equals(E2eSettings.TOPIC))
                .map(columns -> columns[5])
                .filter(value -> value.matches("\\d+"))
                .mapToLong(Long::parseLong)
                .sum();
        assertThat(lag).isZero();
    }
}
