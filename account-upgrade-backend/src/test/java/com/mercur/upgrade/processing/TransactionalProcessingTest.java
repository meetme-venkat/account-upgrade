package com.mercur.upgrade.processing;

import com.mercur.upgrade.IntegrationTestSupport;
import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.eligibility.EligibilityService;
import com.mercur.upgrade.notification.EmailSender;
import com.mercur.upgrade.notification.NotificationService;
import com.mercur.upgrade.notification.impl.EmailSenderImpl;
import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.persistence.ProcessingStatus;
import com.mercur.upgrade.processing.impl.UpgradeRequestHandlerImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.eligibleWithParent;
import static com.mercur.upgrade.TestRequests.event;
import static com.mercur.upgrade.TestRequests.request;
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

    @Test
    void aBatchWritesItsDecisionsAndEmailsTogether() {
        springProcessor.handleAll(List.of(event(eligibleWithParent()), event(eligible()),
                event(request("Carol", 40, "5"))));

        assertThat(count("processed_upgrades")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT event_id || '/' || role FROM notification_outbox ORDER BY id").query(String.class)
                .list()).as("user + parent, user, user (declined)")
                .containsExactly("evt-u-2/USER", "evt-u-2/PARENT", "evt-u-1/USER", "evt-u-3/USER");
    }

    @Test
    void aBatchWhoseEmailsFailStoresNoDecisionSoTheEventsCanBeRetried() {
        List<UpgradeRequestedEvent> batch = List.of(event(eligibleWithParent()), event(eligible()));
        EmailSender failingOutbox = new EmailSender() {
            @Override
            public void send(com.mercur.upgrade.notification.EmailMessage message) {
                outbox.send(message);
            }

            @Override
            public void sendAll(List<com.mercur.upgrade.notification.EmailMessage> messages) {
                throw new DataAccessResourceFailureException("database went away");
            }
        };

        assertThatThrownBy(() -> instance(failingOutbox, repository).handleAll(batch))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("processed_upgrades")).as("the decisions are rolled back with the emails").isZero();
        assertThat(count("notification_outbox")).isZero();

        springProcessor.handleAll(batch); // the broker's retry

        assertThat(count("processed_upgrades")).isEqualTo(2);
        assertThat(count("notification_outbox")).isEqualTo(3);
    }

    @Test
    void aRedeliveredBatchIsSkippedAndOnlyItsNewEventsAreProcessed() {
        UpgradeRequestedEvent first = event(eligibleWithParent());
        springProcessor.handle(first);

        springProcessor.handleAll(List.of(first, event(eligible())));
        springProcessor.handleAll(List.of(first, event(eligible())));

        assertThat(count("processed_upgrades")).isEqualTo(2);
        assertThat(count("notification_outbox")).isEqualTo(3);
    }

    @Test
    void aBatchKeepsEachUsersOrder() {
        List<UpgradeRequestedEvent> sameUser = IntStream.range(0, 20)
                .mapToObj(i -> new UpgradeRequestedEvent("evt-" + i, RequestSource.BATCH, clock.instant(),
                        new UpgradeRequest("u-1", "Alice", i % 2 == 0 ? 20 : 30, new BigDecimal("50"), null)))
                .toList();

        springProcessor.handleAll(sameUser);

        assertThat(repository.findAll()).extracting(ProcessedUpgrade::status).containsExactlyElementsOf(
                IntStream.range(0, 20)
                        .mapToObj(i -> i % 2 == 0 ? ProcessingStatus.ELIGIBLE : ProcessingStatus.INELIGIBLE)
                        .toList());
    }

    @Test
    void twoInstancesRacingOnOneBatchCommitOneDecisionAndOneSetOfEmailsPerEvent() throws Exception {
        List<UpgradeRequestedEvent> batch = List.of(event(eligibleWithParent()), event(eligible()));
        // Both instances find the events unprocessed before either writes anything.
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        ProcessedUpgradeRepository checkThenWait = new DelegatingRepository(repository) {
            @Override
            public Set<String> findStoredEventIds(Collection<String> eventIds) {
                Set<String> stored = super.findStoredEventIds(eventIds);
                try {
                    bothChecked.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return stored;
            }
        };
        UpgradeRequestHandlerImpl serverA = instance(outbox, checkThenWait);
        UpgradeRequestHandlerImpl serverB = instance(outbox, checkThenWait);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> serverA.handleAll(batch));
            Future<?> b = pool.submit(() -> serverB.handleAll(batch));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("processed_upgrades")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT event_id || '/' || role FROM notification_outbox ORDER BY event_id, role")
                .query(String.class).list())
                .as("exactly one email per recipient")
                .containsExactly("evt-u-1/USER", "evt-u-2/PARENT", "evt-u-2/USER");
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
        public Set<String> saveAllIfAbsent(List<ProcessedUpgrade> processedUpgrades) {
            return delegate.saveAllIfAbsent(processedUpgrades);
        }

        @Override
        public boolean existsByEventId(String eventId) {
            return delegate.existsByEventId(eventId);
        }

        @Override
        public Set<String> findStoredEventIds(Collection<String> eventIds) {
            return delegate.findStoredEventIds(eventIds);
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
