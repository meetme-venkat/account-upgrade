package com.mercur.upgrade.persistence.impl;

import com.mercur.upgrade.IntegrationTestSupport;
import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.notification.EmailMessage;
import com.mercur.upgrade.notification.RecipientRole;
import com.mercur.upgrade.notification.impl.EmailSenderImpl;
import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.persistence.ProcessingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.mercur.upgrade.TestRequests.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessedUpgradeRepositoryImplTest extends IntegrationTestSupport {

    @Autowired
    private ProcessedUpgradeRepository repository;

    @Autowired
    private EmailSenderImpl outbox;

    private static ProcessedUpgrade decision(String eventId, String userId, ProcessingStatus status, String... reasons) {
        return new ProcessedUpgrade(eventId, userId, RequestSource.BATCH, status, List.of(reasons), true, NOW);
    }

    @Test
    void isTheJdbcAdapter() {
        assertThat(repository).isInstanceOf(ProcessedUpgradeRepositoryImpl.class);
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
    void savesABatchInListOrderAndReturnsOnlyWhatItStored() {
        repository.saveIfAbsent(decision("e2", "u1", ProcessingStatus.ELIGIBLE));

        Set<String> stored = repository.saveAllIfAbsent(List.of(
                decision("e1", "u1", ProcessingStatus.ELIGIBLE),
                decision("e2", "other", ProcessingStatus.INELIGIBLE, "already there"),
                decision("e3", "u1", ProcessingStatus.INELIGIBLE, "r1", "r2")));

        assertThat(stored).containsExactly("e1", "e3");
        assertThat(repository.findAll()).extracting(ProcessedUpgrade::eventId).containsExactly("e2", "e1", "e3");
        assertThat(repository.findAll().get(2))
                .isEqualTo(decision("e3", "u1", ProcessingStatus.INELIGIBLE, "r1", "r2"));
        assertThat(repository.saveAllIfAbsent(List.of())).isEmpty();
    }

    @Test
    void savesAndFindsBatchesLargerThanOneStatement() {
        int size = ProcessedUpgradeRepositoryImpl.MAX_ROWS_PER_STATEMENT * 2 + 7;
        List<ProcessedUpgrade> records = IntStream.range(0, size)
                .mapToObj(i -> decision("e" + i, "u1", ProcessingStatus.ELIGIBLE)).toList();

        assertThat(repository.saveAllIfAbsent(records)).hasSize(size);

        assertThat(repository.findAll()).extracting(ProcessedUpgrade::eventId)
                .containsExactlyElementsOf(records.stream().map(ProcessedUpgrade::eventId).toList());
        List<String> asked = new ArrayList<>(records.stream().map(ProcessedUpgrade::eventId).toList());
        asked.add("not-stored");
        assertThat(repository.findStoredEventIds(asked)).hasSize(size).doesNotContain("not-stored");
        assertThat(repository.saveAllIfAbsent(records)).as("all duplicates now").isEmpty();
    }

    @Test
    void findsWhichEventIdsAreStored() {
        repository.saveIfAbsent(decision("e1", "u1", ProcessingStatus.ELIGIBLE));
        repository.saveIfAbsent(decision("e3", "u1", ProcessingStatus.ELIGIBLE));

        assertThat(repository.findStoredEventIds(List.of("e1", "e2", "e3"))).containsExactlyInAnyOrder("e1", "e3");
        assertThat(repository.findStoredEventIds(List.of())).isEmpty();
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
