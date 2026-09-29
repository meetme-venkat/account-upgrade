package com.mercur.upgrade.processing.impl;

import com.mercur.upgrade.IntegrationTestSupport;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.eligibility.EligibilityResult;
import com.mercur.upgrade.eligibility.EligibilityService;
import com.mercur.upgrade.notification.NotificationService;
import com.mercur.upgrade.notification.impl.EmailSenderImpl;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Idempotency of one instance when the same event is delivered concurrently (e.g. Kafka redelivery). */
class UpgradeRequestHandlerImplConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    private ProcessedUpgradeRepository repository;
    @Autowired
    private EmailSenderImpl outbox;
    @Autowired
    private TransactionOperations transactions;
    @Autowired
    private Clock clock;

    private UpgradeRequestHandlerImpl processor(EligibilityService eligibility) {
        return new UpgradeRequestHandlerImpl(eligibility, new NotificationService(outbox, clock), repository, clock,
                transactions);
    }

    @Test
    void concurrentDuplicateDeliveriesNotifyAndStoreOnlyOnce() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        EligibilityService eligibility = mock(EligibilityService.class);
        when(eligibility.evaluate(any())).thenAnswer(invocation -> {
            bothInside.countDown();
            bothInside.await(200, TimeUnit.MILLISECONDS); // widen the race window
            return EligibilityResult.fromFailures(List.of());
        });
        UpgradeRequestHandlerImpl processor = processor(eligibility);
        UpgradeRequestedEvent event = event(eligible());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = IntStream.range(0, 8)
                    .<Future<?>>mapToObj(i -> pool.submit(() -> processor.handle(event)))
                    .toList();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("processed_upgrades")).isEqualTo(1);
        assertThat(count("notification_outbox")).as("user must be emailed exactly once").isEqualTo(1);
    }

    @Test
    void failedProcessingReleasesTheEventSoARetryCanProcessIt() {
        EligibilityService eligibility = mock(EligibilityService.class);
        when(eligibility.evaluate(any()))
                .thenThrow(new IllegalStateException("transient"))
                .thenReturn(EligibilityResult.fromFailures(List.of()));
        UpgradeRequestHandlerImpl processor = processor(eligibility);
        UpgradeRequestedEvent event = event(eligible());

        try {
            processor.handle(event);
        } catch (IllegalStateException expected) {
            // first attempt fails; Kafka would retry
        }
        processor.handle(event);

        assertThat(count("processed_upgrades")).isEqualTo(1);
    }
}
