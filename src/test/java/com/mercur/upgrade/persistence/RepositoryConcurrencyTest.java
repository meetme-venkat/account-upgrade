package com.mercur.upgrade.persistence;

import com.mercur.upgrade.common.RequestSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mercur.upgrade.TestRequests.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class RepositoryConcurrencyTest {

    @Test
    void concurrentSavesOfSameEventIdStoreExactlyOne() throws Exception {
        for (int round = 0; round < 500; round++) {
            InMemoryProcessedUpgradeRepository repository = new InMemoryProcessedUpgradeRepository();
            AtomicInteger winners = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            for (int t = 0; t < 8; t++) {
                pool.submit(() -> {
                    go.await();
                    if (repository.saveIfAbsent(record("same"))) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(winners).hasValue(1);
            assertThat(repository.findAll()).hasSize(1);
        }
    }

    @Test
    void readsAreSafeWhileWritersAreActive() throws Exception {
        InMemoryProcessedUpgradeRepository repository = new InMemoryProcessedUpgradeRepository();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        for (int w = 0; w < 4; w++) {
            int writer = w;
            pool.submit(() -> {
                for (int i = 0; i < 20_000; i++) {
                    repository.saveIfAbsent(record("w" + writer + "-" + i));
                }
            });
        }
        pool.submit(() -> {
            int previous = 0;
            while (previous < 80_000) {
                int size = repository.findAll().size(); // must never throw ConcurrentModificationException
                assertThat(size).isGreaterThanOrEqualTo(previous);
                previous = size;
            }
            return null;
        }).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(repository.findAll()).hasSize(80_000);
    }

    private static ProcessedUpgrade record(String eventId) {
        return new ProcessedUpgrade(eventId, "u", RequestSource.BATCH, ProcessingStatus.ELIGIBLE, List.of(), true, NOW);
    }
}
