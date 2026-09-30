package com.mercur.upgrade.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Delivers emails recorded in {@code notification_outbox}.
 *
 * <p>Each poll claims a batch of due rows with {@code FOR UPDATE SKIP LOCKED}, so every instance can run
 * a relay and no row is claimed by two of them. A failed delivery is retried with exponential backoff
 * until {@code max-attempts}; the row then stays undelivered (visible as {@code notificationSent=false}
 * and in the {@code upgrade.notifications.failed} gauge).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final TransactionOperations transactions;
    private final EmailChannel channel;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retention;
    private final Counter sent;

    public OutboxRelay(JdbcClient jdbc, TransactionOperations transactions, EmailChannel channel, Clock clock,
                       MeterRegistry meterRegistry,
                       @Value("${upgrade.notification.relay.batch-size:20}") int batchSize,
                       @Value("${upgrade.notification.relay.max-attempts:8}") int maxAttempts,
                       @Value("${upgrade.notification.relay.retention:7d}") Duration retention) {
        this.jdbc = jdbc;
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
     * Claims the due rows oldest first. The order matches the partial index {@code notification_outbox_pending}
     * ({@code next_attempt_at, id WHERE sent_at IS NULL}), so only pending rows are read. Ordering by {@code id}
     * alone let PostgreSQL walk the primary key past every delivered row once a large backlog made it expect many
     * matches: 600 ms per batch with 1.3 million delivered rows kept for the retention period, versus 2 ms
     * (deploy/load-test/batch-writes.md).
     *
     * @return the number of rows claimed in this batch
     */
    int relayBatch() {
        Integer claimed = transactions.execute(status -> {
            List<OutboxRow> rows = jdbc.sql("""
                            SELECT id, event_id, role, recipient, subject, body, created_at, attempts
                            FROM notification_outbox
                            WHERE sent_at IS NULL AND attempts < :maxAttempts AND next_attempt_at <= :now
                            ORDER BY next_attempt_at, id
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED""")
                    .param("maxAttempts", maxAttempts)
                    .param("now", now())
                    .param("batchSize", batchSize)
                    .query((rs, rowNum) -> new OutboxRow(rs.getLong("id"), rs.getInt("attempts"), new EmailMessage(
                            rs.getString("event_id"),
                            RecipientRole.valueOf(rs.getString("role")),
                            rs.getString("recipient"),
                            rs.getString("subject"),
                            rs.getString("body"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant())))
                    .list();
            List<Long> delivered = new ArrayList<>(rows.size());
            for (OutboxRow row : rows) {
                if (deliver(row)) {
                    delivered.add(row.id());
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
        jdbc.sql("UPDATE notification_outbox SET sent_at = :now, attempts = attempts + 1, last_error = NULL "
                        + "WHERE id IN (:ids)")
                .param("now", now())
                .param("ids", ids)
                .update();
        sent.increment(ids.size());
    }

    /**
     * Delivers one row. A failure is recorded on the row (attempts, error, next attempt) right away.
     *
     * @return whether it was delivered; delivered rows are marked sent together, by {@link #markSent}
     */
    private boolean deliver(OutboxRow row) {
        EmailMessage message = row.message();
        try {
            channel.deliver(message, message.eventId() + ":" + message.role());
        } catch (RuntimeException e) {
            int attempts = row.attempts() + 1;
            Duration backoff = backoff(attempts);
            jdbc.sql("UPDATE notification_outbox SET attempts = :attempts, last_error = :error, "
                            + "next_attempt_at = :next WHERE id = :id")
                    .param("attempts", attempts)
                    .param("error", String.valueOf(e.getMessage()))
                    .param("next", OffsetDateTime.ofInstant(clock.instant().plus(backoff), ZoneOffset.UTC))
                    .param("id", row.id())
                    .update();
            if (attempts >= maxAttempts) {
                log.error("Giving up on {} notification for event {} after {} attempts", message.role(),
                        message.eventId(), attempts, e);
            } else {
                log.warn("Delivery of {} notification for event {} failed (attempt {}), retrying in {}",
                        message.role(), message.eventId(), attempts, backoff, e);
            }
            return false;
        }
        return true;
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
        int deleted = jdbc.sql("DELETE FROM notification_outbox WHERE sent_at IS NOT NULL AND sent_at < :cutoff")
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
        if (deleted > 0) {
            log.info("Purged {} delivered notifications older than {}", deleted, retention);
        }
        return deleted;
    }

    long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM notification_outbox WHERE sent_at IS NULL AND attempts < :maxAttempts")
                .param("maxAttempts", maxAttempts).query(Long.class).single();
    }

    long failedCount() {
        return jdbc.sql("SELECT count(*) FROM notification_outbox WHERE sent_at IS NULL AND attempts >= :maxAttempts")
                .param("maxAttempts", maxAttempts).query(Long.class).single();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record OutboxRow(long id, int attempts, EmailMessage message) {
    }
}
