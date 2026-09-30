package com.mercur.upgrade.notification;

import com.mercur.upgrade.notification.jpa.OutboxEmailEntity;
import com.mercur.upgrade.notification.jpa.OutboxEmailJpaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Delivers emails recorded in {@code notification_outbox}.
 *
 * <p>Each poll claims a batch of due rows with {@code FOR ... SKIP LOCKED}, so every instance can run a relay and no
 * row is claimed by two of them. A failed delivery is retried with exponential backoff until {@code max-attempts};
 * the row then stays undelivered (visible as {@code notificationSent=false} and in the
 * {@code upgrade.notifications.failed} gauge).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final OutboxEmailJpaRepository outbox;
    private final TransactionOperations transactions;
    private final EmailChannel channel;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retention;
    private final Counter sent;

    public OutboxRelay(OutboxEmailJpaRepository outbox, TransactionOperations transactions, EmailChannel channel,
                       Clock clock, MeterRegistry meterRegistry,
                       @Value("${upgrade.notification.relay.batch-size:20}") int batchSize,
                       @Value("${upgrade.notification.relay.max-attempts:8}") int maxAttempts,
                       @Value("${upgrade.notification.relay.retention:7d}") Duration retention) {
        this.outbox = outbox;
        this.transactions = transactions;
        this.channel = channel;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retention = retention;
        // Per instance: each relay counts only what it delivered, so summing across instances is correct.
        this.sent = Counter.builder("upgrade.notifications.sent").description("Emails delivered by this instance")
                .register(meterRegistry);
        // Global (whole table): aggregate these with max, not sum, across instances.
        Gauge.builder("upgrade.notifications.pending", this, OutboxRelay::pendingCount)
                .description("Emails waiting for delivery or retry").register(meterRegistry);
        Gauge.builder("upgrade.notifications.failed", this, OutboxRelay::failedCount)
                .description("Emails that exhausted their delivery attempts").register(meterRegistry);
    }

    /** Delivers every due row, one batch (and one transaction) at a time. */
    @Scheduled(fixedDelayString = "${upgrade.notification.relay.interval:500ms}")
    public void relayPending() {
        int claimed;
        do {
            claimed = relayBatch();
        } while (claimed == batchSize);
    }

    /**
     * Claims the due rows oldest first ({@link OutboxEmailJpaRepository#claimDue}), delivers them, and marks the
     * delivered ones sent with one statement. The order matches the partial index {@code notification_outbox_pending}
     * ({@code next_attempt_at, id WHERE sent_at IS NULL}), so only pending rows are read. Ordering by {@code id}
     * alone let PostgreSQL walk the primary key past every delivered row once a large backlog made it expect many
     * matches: 600 ms per batch with 1.3 million delivered rows kept for the retention period, versus 2 ms
     * (deploy/load-test/batch-writes.md).
     *
     * @return the number of rows claimed in this batch
     */
    int relayBatch() {
        Integer claimed = transactions.execute(status -> {
            List<OutboxEmailEntity> rows = outbox.claimDue(maxAttempts, clock.instant(), Limit.of(batchSize));
            List<Long> delivered = new ArrayList<>(rows.size());
            for (OutboxEmailEntity row : rows) {
                if (deliver(row)) {
                    delivered.add(row.getId());
                }
            }
            markSent(delivered);
            return rows.size();
        });
        return claimed == null ? 0 : claimed;
    }

    /** One statement for the whole batch: a round trip per email was most of the relay's time. */
    private void markSent(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        outbox.markSent(ids, clock.instant());
        sent.increment(ids.size());
    }

    /**
     * Delivers one row. A failure is recorded on the (managed) row, and written when the batch commits.
     *
     * @return whether it was delivered; delivered rows are marked sent together, by {@link #markSent}
     */
    private boolean deliver(OutboxEmailEntity row) {
        EmailMessage message = row.toMessage();
        try {
            channel.deliver(message, message.eventId() + ":" + message.role());
            return true;
        } catch (RuntimeException e) {
            Duration backoff = backoff(row.getAttempts() + 1);
            row.recordFailure(String.valueOf(e.getMessage()), clock.instant().plus(backoff));
            if (row.getAttempts() >= maxAttempts) {
                log.error("Giving up on {} notification for event {} after {} attempts", message.role(),
                        message.eventId(), row.getAttempts(), e);
            } else {
                log.warn("Delivery of {} notification for event {} failed (attempt {}), retrying in {}",
                        message.role(), message.eventId(), row.getAttempts(), backoff, e);
            }
            return false;
        }
    }

    /** 1 s, 2 s, 4 s, ... capped at 5 minutes. */
    static Duration backoff(int attempts) {
        Duration delay = Duration.ofSeconds(1L << Math.min(attempts - 1, 20));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /** Deletes delivered rows past the retention period (they hold personal data). Undelivered rows are kept. */
    @Scheduled(cron = "${upgrade.notification.relay.retention-cron:0 17 3 * * *}")
    public int purgeDelivered() {
        Instant cutoff = clock.instant().minus(retention);
        Integer deleted = transactions.execute(status -> outbox.deleteDeliveredBefore(cutoff));
        if (deleted != null && deleted > 0) {
            log.info("Purged {} delivered notifications older than {}", deleted, retention);
        }
        return deleted == null ? 0 : deleted;
    }

    long pendingCount() {
        return outbox.countBySentAtIsNullAndAttemptsLessThan(maxAttempts);
    }

    long failedCount() {
        return outbox.countBySentAtIsNullAndAttemptsGreaterThanEqual(maxAttempts);
    }
}
