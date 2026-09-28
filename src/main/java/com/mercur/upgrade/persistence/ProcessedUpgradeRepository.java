package com.mercur.upgrade.persistence;

import java.util.List;

/**
 * Storage port for processed requests. The in-memory implementation can be replaced by a
 * PostgreSQL-backed one (e.g. Spring Data JDBC with {@code event_id} as primary key)
 * without touching the processing logic.
 */
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
}
