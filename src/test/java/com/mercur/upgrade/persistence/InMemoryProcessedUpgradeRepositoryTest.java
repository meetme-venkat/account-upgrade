package com.mercur.upgrade.persistence;

import com.mercur.upgrade.common.RequestSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static com.mercur.upgrade.TestRequests.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryProcessedUpgradeRepositoryTest {

    private final InMemoryProcessedUpgradeRepository repository = new InMemoryProcessedUpgradeRepository();

    @Test
    void returnsRecordsInStoreOrderEvenWhenTimestampsAreEqual() {
        List<String> eventIds = IntStream.range(0, 100).mapToObj(i -> "evt-" + i).toList();
        eventIds.forEach(id -> repository.saveIfAbsent(record(id)));

        assertThat(repository.findAll()).extracting(ProcessedUpgrade::eventId).containsExactlyElementsOf(eventIds);
    }

    @Test
    void rejectsDuplicateEventId() {
        assertThat(repository.saveIfAbsent(record("evt-1"))).isTrue();
        assertThat(repository.saveIfAbsent(record("evt-1"))).isFalse();

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.existsByEventId("evt-1")).isTrue();
        assertThat(repository.existsByEventId("evt-2")).isFalse();
    }

    private static ProcessedUpgrade record(String eventId) {
        return new ProcessedUpgrade(eventId, "u-1", RequestSource.BATCH, ProcessingStatus.ELIGIBLE, List.of(), true, NOW);
    }
}
