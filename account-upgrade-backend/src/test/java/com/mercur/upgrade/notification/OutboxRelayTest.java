package com.mercur.upgrade.notification;

import com.mercur.upgrade.IntegrationTestSupport;
import com.mercur.upgrade.MutableClock;
import com.mercur.upgrade.notification.impl.EmailSenderImpl;
import com.mercur.upgrade.notification.jpa.OutboxEmailJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayTest extends IntegrationTestSupport {

    private static final Instant START = Instant.parse("2026-09-28T10:00:00Z");
    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private EmailSenderImpl outbox;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private OutboxEmailJpaRepository outboxRows;

    private final MutableClock clock = new MutableClock(START);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private OutboxRelay relay(EmailChannel channel, int batchSize) {
        return new OutboxRelay(outboxRows, transactions, channel, clock, meters, batchSize, MAX_ATTEMPTS, Duration.ofDays(7));
    }

    private void enqueue(int count) {
        for (int i = 0; i < count; i++) {
            outbox.send(new EmailMessage("e" + i, RecipientRole.USER, "u" + i, "subject " + i, "body", START));
        }
    }

    private double sentCounter() {
        return meters.get("upgrade.notifications.sent").counter().count();
    }

    @Test
    void deliversPendingRowsMarksThemSentAndCounts() {
        enqueue(3);
        List<String> delivered = new CopyOnWriteArrayList<>();

        relay((message, key) -> delivered.add(key), 20).relayPending();

        assertThat(delivered).containsExactly("e0:USER", "e1:USER", "e2:USER");
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox WHERE sent_at IS NOT NULL").query(Long.class).single())
                .isEqualTo(3);
        assertThat(sentCounter()).isEqualTo(3);
        assertThat(meters.get("upgrade.notifications.pending").gauge().value()).isZero();
    }

    @Test
    void deliversTheOldestDueRowsFirst() {
        enqueue(3);
        // e0 waits for a retry, due after e1 and e2 although it was recorded first.
        jdbc.sql("UPDATE notification_outbox SET next_attempt_at = :later WHERE event_id = 'e0'")
                .param("later", java.time.OffsetDateTime.ofInstant(START.plusSeconds(10), java.time.ZoneOffset.UTC))
                .update();
        List<String> delivered = new CopyOnWriteArrayList<>();

        relay((message, key) -> delivered.add(key), 20).relayPending();
        assertThat(delivered).containsExactly("e1:USER", "e2:USER");

        clock.advance(Duration.ofSeconds(10));
        relay((message, key) -> delivered.add(key), 20).relayPending();
        assertThat(delivered).containsExactly("e1:USER", "e2:USER", "e0:USER");
    }

    @Test
    void drainsSeveralBatchesInOnePoll() {
        enqueue(7);
        AtomicInteger delivered = new AtomicInteger();

        relay((message, key) -> delivered.incrementAndGet(), 3).relayPending();

        assertThat(delivered).hasValue(7);
    }

    @Test
    void retriesWithBackoffThenGivesUpAfterMaxAttempts() {
        enqueue(1);
        AtomicInteger calls = new AtomicInteger();
        OutboxRelay relay = relay((message, key) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("SES throttled");
        }, 20);

        relay.relayPending();
        assertThat(calls).hasValue(1);
        assertThat(jdbc.sql("SELECT last_error FROM notification_outbox").query(String.class).single())
                .isEqualTo("SES throttled");

        relay.relayPending();
        assertThat(calls).as("not due again until the 1 s backoff has passed").hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        relay.relayPending();
        clock.advance(Duration.ofSeconds(2));
        relay.relayPending();
        assertThat(calls).hasValue(MAX_ATTEMPTS);

        clock.advance(Duration.ofHours(1));
        relay.relayPending();
        assertThat(calls).as("exhausted rows are no longer claimed").hasValue(MAX_ATTEMPTS);
        assertThat(meters.get("upgrade.notifications.failed").gauge().value()).isEqualTo(1);
        assertThat(meters.get("upgrade.notifications.pending").gauge().value()).isZero();
    }

    @Test
    void oneFailingRowDoesNotBlockTheOthers() {
        enqueue(3);
        List<String> delivered = new CopyOnWriteArrayList<>();

        relay((message, key) -> {
            if (key.equals("e1:USER")) {
                throw new IllegalStateException("bad address");
            }
            delivered.add(key);
        }, 20).relayPending();

        assertThat(delivered).containsExactly("e0:USER", "e2:USER");
    }

    @Test
    void twoRelaysInParallelDeliverEveryRowExactlyOnce() throws Exception {
        int rows = 60;
        enqueue(rows);
        List<String> delivered = new CopyOnWriteArrayList<>();
        EmailChannel slowChannel = (message, key) -> {
            delivered.add(key);
            sleep(5); // keep both relays' batches in flight at the same time
        };
        OutboxRelay first = relay(slowChannel, 5);
        OutboxRelay second = relay(slowChannel, 5);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> await(start, first));
            Future<?> b = pool.submit(() -> await(start, second));
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(delivered).hasSize(rows).doesNotHaveDuplicates();
    }

    @Test
    void purgesOnlyDeliveredRowsPastRetention() {
        enqueue(2);
        relay((message, key) -> {
            if (key.equals("e1:USER")) {
                throw new IllegalStateException("down");
            }
        }, 20).relayPending();
        OutboxRelay relay = relay((message, key) -> { }, 20);

        assertThat(relay.purgeDelivered()).as("within retention").isZero();

        clock.advance(Duration.ofDays(8));
        assertThat(relay.purgeDelivered()).isEqualTo(1);
        assertThat(count("notification_outbox")).as("the undelivered row is kept").isEqualTo(1);
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(OutboxRelay.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxRelay.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(OutboxRelay.backoff(30)).isEqualTo(Duration.ofMinutes(5));
    }

    private static void await(CountDownLatch start, OutboxRelay relay) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        relay.relayPending();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
