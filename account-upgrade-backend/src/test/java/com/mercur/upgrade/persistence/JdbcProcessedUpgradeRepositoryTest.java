package com.mercur.upgrade.persistence;

import com.mercur.upgrade.IntegrationTestSupport;
import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.notification.EmailMessage;
import com.mercur.upgrade.notification.OutboxEmailSender;
import com.mercur.upgrade.notification.RecipientRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static com.mercur.upgrade.TestRequests.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcProcessedUpgradeRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private ProcessedUpgradeRepository repository;

    @Autowired
    private OutboxEmailSender outbox;

    private static ProcessedUpgrade decision(String eventId, String userId, ProcessingStatus status, String... reasons) {
        return new ProcessedUpgrade(eventId, userId, RequestSource.BATCH, status, List.of(reasons), true, NOW);
    }

    @Test
    void isTheJdbcAdapter() {
        assertThat(repository).isInstanceOf(JdbcProcessedUpgradeRepository.class);
    }

    @Test
    void storesOnceAndRoundTripsEveryField() {
        ProcessedUpgrade declined = decision("e1", "u1", ProcessingStatus.INELIGIBLE,
                "User name must not be empty", "Balance must be at least $30 but was $12.75");

        assertThat(repository.saveIfAbsent(declined)).isTrue();
        assertThat(repository.saveIfAbsent(decision("e1", "other", ProcessingStatus.ELIGIBLE))).isFalse();

        assertThat(repository.existsByEventId("e1")).isTrue();
        assertThat(repository.existsByEventId("nope")).isFalse();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll()).containsExactly(declined);
    }

    @Test
    void keepsStoreOrderEvenWithIdenticalTimestamps() {
        for (int i = 0; i < 20; i++) {
            repository.saveIfAbsent(decision("e" + i, "u1", ProcessingStatus.ELIGIBLE));
        }

        assertThat(repository.findAll()).extracting(ProcessedUpgrade::eventId)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 20).mapToObj(i -> "e" + i).toList());
    }

    @Test
    void findsTheNewestMatchesInStoreOrder() {
        repository.saveIfAbsent(decision("e1", "u1", ProcessingStatus.ELIGIBLE));
        repository.saveIfAbsent(decision("e2", "u2", ProcessingStatus.INELIGIBLE, "r"));
        repository.saveIfAbsent(decision("e3", "u1", ProcessingStatus.INELIGIBLE, "r"));
        repository.saveIfAbsent(decision("e4", "u1", ProcessingStatus.INELIGIBLE, "r"));

        assertThat(ids(repository.find(null, null, 10))).containsExactly("e1", "e2", "e3", "e4");
        assertThat(ids(repository.find(ProcessingStatus.INELIGIBLE, null, 10))).containsExactly("e2", "e3", "e4");
        assertThat(ids(repository.find(null, "u1", 10))).containsExactly("e1", "e3", "e4");
        assertThat(ids(repository.find(ProcessingStatus.INELIGIBLE, "u1", 1))).containsExactly("e4");
        assertThat(ids(repository.find(null, null, 2))).containsExactly("e3", "e4");
    }

    @Test
    void notificationSentReflectsDeliveryFromTheOutbox() {
        repository.saveIfAbsent(decision("e1", "u1", ProcessingStatus.ELIGIBLE));
        outbox.send(new EmailMessage("e1", RecipientRole.USER, "u1", "s", "b", NOW));
        outbox.send(new EmailMessage("e1", RecipientRole.PARENT, "p@example.com", "s", "b", NOW));

        assertThat(repository.findAll().get(0).notificationSent()).as("both pending").isFalse();

        markSent("USER");
        assertThat(repository.findAll().get(0).notificationSent()).as("parent still pending").isFalse();

        markSent("PARENT");
        assertThat(repository.findAll().get(0).notificationSent()).isTrue();
    }

    private void markSent(String role) {
        jdbc.sql("UPDATE notification_outbox SET sent_at = :now WHERE role = :role")
                .param("now", java.time.OffsetDateTime.ofInstant(Instant.now(), java.time.ZoneOffset.UTC))
                .param("role", role)
                .update();
    }

    private static List<String> ids(List<ProcessedUpgrade> records) {
        return records.stream().map(ProcessedUpgrade::eventId).toList();
    }
}
