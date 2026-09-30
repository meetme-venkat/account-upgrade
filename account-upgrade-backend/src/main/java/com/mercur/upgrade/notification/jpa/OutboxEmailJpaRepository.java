package com.mercur.upgrade.notification.jpa;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Spring Data access to {@code notification_outbox}, for the outbox writer, the relay and the notification log. */
public interface OutboxEmailJpaRepository extends JpaRepository<OutboxEmailEntity, Long> {

    /**
     * Locks the oldest due emails that no other transaction has locked ({@code FOR ... SKIP LOCKED}: lock timeout
     * -2 is Hibernate's "skip locked"), so relays on several instances never claim the same row. The order is that
     * of the partial index {@code notification_outbox_pending}, so only pending rows are read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select o from OutboxEmailEntity o
            where o.sentAt is null and o.attempts < :maxAttempts and o.nextAttemptAt <= :now
            order by o.nextAttemptAt, o.id""")
    List<OutboxEmailEntity> claimDue(int maxAttempts, Instant now, Limit limit);

    /** Marks delivered emails sent, with one statement. */
    @Modifying
    @Query("""
            update OutboxEmailEntity o set o.sentAt = :now, o.attempts = o.attempts + 1, o.lastError = null
            where o.id in :ids""")
    int markSent(Collection<Long> ids, Instant now);

    @Modifying
    @Query("delete from OutboxEmailEntity o where o.sentAt is not null and o.sentAt < :cutoff")
    int deleteDeliveredBefore(Instant cutoff);

    long countBySentAtIsNullAndAttemptsLessThan(int maxAttempts);

    long countBySentAtIsNullAndAttemptsGreaterThanEqual(int maxAttempts);

    /** The newest delivered emails, newest first. */
    List<OutboxEmailEntity> findBySentAtIsNotNullOrderByIdDesc(Limit limit);
}
