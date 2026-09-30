package com.mercur.upgrade.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Storage port for processed requests, implemented by
 * {@link com.mercur.upgrade.persistence.impl.ProcessedUpgradeRepositoryImpl}.
 */
public interface ProcessedUpgradeRepository {

    /**
     * Stores the record unless one with the same event id already exists.
     *
     * @return {@code true} if stored, {@code false} if it was a duplicate
     */
    boolean saveIfAbsent(ProcessedUpgrade processedUpgrade);

    /**
     * Stores the records whose event id does not exist yet, in list order (the store order of each user), with as
     * few statements as possible.
     *
     * @return the event ids stored by this call; an event id already stored, or stored concurrently by another
     *         transaction, is not in it
     */
    Set<String> saveAllIfAbsent(List<ProcessedUpgrade> processedUpgrades);

    boolean existsByEventId(String eventId);

    /** The given event ids that are already stored. */
    Set<String> findStoredEventIds(Collection<String> eventIds);

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
