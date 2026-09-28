package com.mercur.upgrade.processing;

import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.eligibility.EligibilityResult;
import com.mercur.upgrade.eligibility.EligibilityService;
import com.mercur.upgrade.notification.EmailSender;
import com.mercur.upgrade.notification.NotificationService;
import com.mercur.upgrade.persistence.InMemoryProcessedUpgradeRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mercur.upgrade.TestRequests.NOW;
import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Idempotency of the consumer when the same event is delivered concurrently (e.g. Kafka rebalance). */
class UpgradeRequestProcessorConcurrencyTest {

    @Test
    void concurrentDuplicateDeliveriesNotifyAndStoreOnlyOnce() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        EligibilityService eligibility = mock(EligibilityService.class);
        when(eligibility.evaluate(any())).thenAnswer(invocation -> {
            bothInside.countDown();
            bothInside.await(200, TimeUnit.MILLISECONDS); // widen the race window
            return EligibilityResult.fromFailures(List.of());
        });
        AtomicInteger emails = new AtomicInteger();
        EmailSender sender = message -> emails.incrementAndGet();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryProcessedUpgradeRepository repository = new InMemoryProcessedUpgradeRepository();
        UpgradeRequestProcessor processor = new UpgradeRequestProcessor(
                eligibility, new NotificationService(sender, clock), repository, clock);

        UpgradeRequestedEvent event = event(eligible());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = java.util.stream.IntStream.range(0, 8)
                .<Future<?>>mapToObj(i -> pool.submit(() -> processor.handle(event)))
                .toList();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(repository.findAll()).hasSize(1);
        assertThat(emails).as("user must be emailed exactly once").hasValue(1);
    }

    @Test
    void failedProcessingReleasesTheEventSoARetryCanProcessIt() {
        EligibilityService eligibility = mock(EligibilityService.class);
        when(eligibility.evaluate(any()))
                .thenThrow(new IllegalStateException("transient"))
                .thenReturn(EligibilityResult.fromFailures(List.of()));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryProcessedUpgradeRepository repository = new InMemoryProcessedUpgradeRepository();
        UpgradeRequestProcessor processor = new UpgradeRequestProcessor(
                eligibility, new NotificationService(message -> { }, clock), repository, clock);
        UpgradeRequestedEvent event = event(eligible());

        try {
            processor.handle(event);
        } catch (IllegalStateException expected) {
            // first attempt fails; the broker would retry
        }
        processor.handle(event);

        assertThat(repository.findAll()).hasSize(1);
    }
}
