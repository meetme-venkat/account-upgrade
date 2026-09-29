package com.mercur.upgrade.persistence;

import java.util.List;

/** Storage port for processed requests, implemented by {@link JdbcProcessedUpgradeRepository}. */
public interface ProcessedUpgradeRepository {

    /**
     * Stores the record unless one with the same event id already exists.
     *
     * @return {@code true} if stored, {@code false} if it was a duplicate
     */
    boolean saveIfAbsent(ProcessedUpgrade processedUpgrade);

    boolean existsByEventId(String eventId);

    long count();

    /** All records in the order they were stored (per user, this is processing order). */
    List<ProcessedUpgrade> findAll();

    /**
     * The newest {@code limit} records matching the filters, returned in store order.
     *
     * @param status {@code null} for any status
     * @param userId {@code null} for any user
     */
    List<ProcessedUpgrade> find(ProcessingStatus status, String userId, int limit);
}
