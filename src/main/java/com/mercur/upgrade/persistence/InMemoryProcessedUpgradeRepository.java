package com.mercur.upgrade.persistence;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe in-memory store keyed by event id.
 *
 * <p>Insertion order is tracked separately rather than sorting by {@code processedAt}: clock
 * resolution can give several records the same timestamp, and ties would then be returned in
 * arbitrary order, breaking the per-user processing order.
 */
@Repository
public class InMemoryProcessedUpgradeRepository implements ProcessedUpgradeRepository {

    private final Map<String, ProcessedUpgrade> byEventId = new ConcurrentHashMap<>();
    private final Queue<ProcessedUpgrade> inStoreOrder = new ConcurrentLinkedQueue<>();

    @Override
    public boolean saveIfAbsent(ProcessedUpgrade processedUpgrade) {
        if (byEventId.putIfAbsent(processedUpgrade.eventId(), processedUpgrade) != null) {
            return false;
        }
        inStoreOrder.add(processedUpgrade);
        return true;
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return byEventId.containsKey(eventId);
    }

    @Override
    public long count() {
        return byEventId.size();
    }

    @Override
    public List<ProcessedUpgrade> findAll() {
        return List.copyOf(inStoreOrder);
    }
}
