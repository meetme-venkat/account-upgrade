package com.mercur.upgrade.processing;

import com.mercur.upgrade.IntegrationTestSupport;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.eligibility.EligibilityService;
import com.mercur.upgrade.notification.EmailSender;
import com.mercur.upgrade.notification.NotificationService;
import com.mercur.upgrade.notification.impl.EmailSenderImpl;
import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.processing.impl.UpgradeRequestHandlerImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mercur.upgrade.TestRequests.eligibleWithParent;
import static com.mercur.upgrade.TestRequests.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decision and its outbox rows are one transaction: they commit together or not at all, including
 * when two instances process the same event at the same time.
 */
class TransactionalProcessingTest extends IntegrationTestSupport {

    @Autowired
    private EligibilityService eligibilityService;
    @Autowired
    private ProcessedUpgradeRepository repository;
    @Autowired
    private EmailSenderImpl outbox;
    @Autowired
    private TransactionOperations transactions;
    @Autowired
    private Clock clock;
    @Autowired
    private UpgradeRequestHandlerImpl springProcessor;

    /** A separate processor instance (its own in-memory claim), as on another server. */
    private UpgradeRequestHandlerImpl instance(EmailSender sender, ProcessedUpgradeRepository repo) {
        return new UpgradeRequestHandlerImpl(eligibilityService, new NotificationService(sender, clock), repo, clock,
                transactions);
    }

    @Test
    void theSpringProcessorWritesDecisionAndOutboxTogether() {
        springProcessor.handle(event(eligibleWithParent()));

        assertThat(count("processed_upgrades")).isEqualTo(1);
        assertThat(count("notification_outbox")).as("user + parent").isEqualTo(2);
    }

    @Test
    void twoInstancesRacingOnOneEventCommitOneDecisionAndOneSetOfEmails() throws Exception {
        UpgradeRequestedEvent event = event(eligibleWithParent());
        // Both instances pass the "already processed?" check before either writes anything.
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        EmailSender barrierThenOutbox = new EmailSender() {
            private final ThreadLocal<Boolean> waited = ThreadLocal.withInitial(() -> false);

            @Override
            public void send(com.mercur.upgrade.notification.EmailMessage message) {
                if (!waited.get()) {
                    waited.set(true);
                    try {
                        bothChecked.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                outbox.send(message);
            }
        };
        UpgradeRequestHandlerImpl serverA = instance(barrierThenOutbox, repository);
        UpgradeRequestHandlerImpl serverB = instance(barrierThenOutbox, repository);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> serverA.handle(event));
            Future<?> b = pool.submit(() -> serverB.handle(event));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("processed_upgrades")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT role FROM notification_outbox ORDER BY role").query(String.class).list())
                .as("exactly one email per recipient").containsExactly("PARENT", "USER");
    }

    @Test
    void aFailedDecisionSaveRollsBackTheEmailsSoARetryStartsClean() {
        UpgradeRequestedEvent event = event(eligibleWithParent());
        ProcessedUpgradeRepository failingSave = new DelegatingRepository(repository) {
            @Override
            public boolean saveIfAbsent(ProcessedUpgrade processedUpgrade) {
                throw new DataAccessResourceFailureException("database went away");
            }
        };

        assertThatThrownBy(() -> instance(outbox, failingSave).handle(event))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("notification_outbox")).as("no email without a decision").isZero();
        assertThat(count("processed_upgrades")).isZero();

        instance(outbox, repository).handle(event); // the broker's retry

        assertThat(count("processed_upgrades")).isEqualTo(1);
        assertThat(count("notification_outbox")).isEqualTo(2);
    }

    @Test
    void aRedeliveredEventIsSkipped() {
        UpgradeRequestedEvent event = event(eligibleWithParent());
        springProcessor.handle(event);
        springProcessor.handle(event);

        assertThat(count("processed_upgrades")).isEqualTo(1);
        assertThat(count("notification_outbox")).isEqualTo(2);
    }

    /** Forwards everything to a real repository; tests override single methods. */
    private static class DelegatingRepository implements ProcessedUpgradeRepository {
        private final ProcessedUpgradeRepository delegate;

        DelegatingRepository(ProcessedUpgradeRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean saveIfAbsent(ProcessedUpgrade processedUpgrade) {
            return delegate.saveIfAbsent(processedUpgrade);
        }

        @Override
        public boolean existsByEventId(String eventId) {
            return delegate.existsByEventId(eventId);
        }

        @Override
        public long count() {
            return delegate.count();
        }

        @Override
        public List<ProcessedUpgrade> findAll() {
            return delegate.findAll();
        }

        @Override
        public List<ProcessedUpgrade> find(com.mercur.upgrade.persistence.ProcessingStatus status, String userId,
                                           int limit) {
            return delegate.find(status, userId, limit);
        }
    }
}
