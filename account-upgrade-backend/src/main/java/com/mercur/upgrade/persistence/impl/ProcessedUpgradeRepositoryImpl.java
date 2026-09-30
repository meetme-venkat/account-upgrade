package com.mercur.upgrade.persistence.impl;

import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.persistence.ProcessingStatus;
import com.mercur.upgrade.persistence.jpa.ProcessedUpgradeEntity;
import com.mercur.upgrade.persistence.jpa.ProcessedUpgradeJpaRepository;
import com.mercur.upgrade.persistence.jpa.ReasonsConverter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL store shared by all instances, with JPA/Hibernate.
 *
 * <ul>
 *   <li>Inserts are native SQL, {@code INSERT ... ON CONFLICT DO NOTHING}, which JPA cannot express: when two instances
 *       race on one event, the second blocks until the first commits and then inserts nothing, instead of failing on
 *       the primary key. {@link #saveAllIfAbsent} inserts a whole batch in one statement and returns what it stored.
 *       </li>
 *   <li>Everything else is JPQL. {@code notificationSent} is derived when reading, from the notification outbox: it
 *       is true once every notification of the event has been delivered by the outbox relay. The flag passed to
 *       {@link #saveIfAbsent} is therefore ignored.</li>
 * </ul>
 */
@Repository
public class ProcessedUpgradeRepositoryImpl implements ProcessedUpgradeRepository {

    /** Rows per multi-row statement: well below PostgreSQL's 65,535 bind parameters (6 per row). */
    static final int MAX_ROWS_PER_STATEMENT = 1000;

    private static final String COLUMNS = "(event_id, user_id, source, status, reasons, processed_at)";

    /** A record and whether all its emails are delivered (none pending in the outbox). */
    private static final String SELECT = """
            select p, case when exists (select o.id from OutboxEmailEntity o
                                        where o.eventId = p.eventId and o.sentAt is null) then false else true end
            from ProcessedUpgradeEntity p
            """;

    private static final ReasonsConverter REASONS = new ReasonsConverter();

    private final EntityManager entityManager;
    private final ProcessedUpgradeJpaRepository jpa;

    public ProcessedUpgradeRepositoryImpl(EntityManager entityManager, ProcessedUpgradeJpaRepository jpa) {
        this.entityManager = entityManager;
        this.jpa = jpa;
    }

    @Override
    @Transactional // joins the caller's transaction: JPA runs native writes only inside one
    public boolean saveIfAbsent(ProcessedUpgrade processed) {
        Query insert = entityManager.createNativeQuery(
                "INSERT INTO processed_upgrades " + COLUMNS + " VALUES " + placeholders(0)
                        + " ON CONFLICT (event_id) DO NOTHING");
        bind(insert, 0, processed);
        return insert.executeUpdate() == 1;
    }

    /**
     * One multi-row {@code INSERT ... ON CONFLICT DO NOTHING RETURNING event_id} per {@value #MAX_ROWS_PER_STATEMENT}
     * records. PostgreSQL assigns {@code seq} in the order of the {@code VALUES} rows, so the list order is the store
     * order. A record whose event id exists, or is being inserted by another transaction that then commits, is
     * skipped and not returned.
     */
    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Set<String> saveAllIfAbsent(List<ProcessedUpgrade> processedUpgrades) {
        Set<String> stored = new LinkedHashSet<>();
        for (int from = 0; from < processedUpgrades.size(); from += MAX_ROWS_PER_STATEMENT) {
            List<ProcessedUpgrade> rows =
                    processedUpgrades.subList(from, Math.min(from + MAX_ROWS_PER_STATEMENT, processedUpgrades.size()));
            StringBuilder sql = new StringBuilder("INSERT INTO processed_upgrades " + COLUMNS + " VALUES ");
            for (int i = 0; i < rows.size(); i++) {
                sql.append(i == 0 ? "" : ", ").append(placeholders(i));
            }
            sql.append(" ON CONFLICT (event_id) DO NOTHING RETURNING event_id");
            Query insert = entityManager.createNativeQuery(sql.toString(), String.class);
            for (int i = 0; i < rows.size(); i++) {
                bind(insert, i, rows.get(i));
            }
            stored.addAll(insert.getResultList());
        }
        return stored;
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return jpa.existsById(eventId);
    }

    @Override
    public Set<String> findStoredEventIds(Collection<String> eventIds) {
        List<String> ids = List.copyOf(eventIds);
        Set<String> stored = new HashSet<>();
        for (int from = 0; from < ids.size(); from += MAX_ROWS_PER_STATEMENT) {
            int to = Math.min(from + MAX_ROWS_PER_STATEMENT, ids.size());
            stored.addAll(jpa.findStoredEventIds(ids.subList(from, to)));
        }
        return stored;
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public List<ProcessedUpgrade> findAll() {
        return toRecords(entityManager.createQuery(SELECT + "order by p.seq", Object[].class).getResultList());
    }

    @Override
    public List<ProcessedUpgrade> find(ProcessingStatus status, String userId, int limit) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (status != null) {
            conditions.add("p.status = :status");
            params.put("status", status);
        }
        if (userId != null) {
            conditions.add("p.userId = :userId");
            params.put("userId", userId);
        }
        String where = conditions.isEmpty() ? "" : "where " + String.join(" and ", conditions) + " ";
        TypedQuery<Object[]> query = entityManager.createQuery(SELECT + where + "order by p.seq desc", Object[].class)
                .setMaxResults(limit);
        params.forEach(query::setParameter);
        List<ProcessedUpgrade> newestFirst = toRecords(query.getResultList());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    private static String placeholders(int row) {
        return "(:eventId%1$d, :userId%1$d, :source%1$d, :status%1$d, :reasons%1$d, :processedAt%1$d)".formatted(row);
    }

    private static void bind(Query insert, int row, ProcessedUpgrade processed) {
        insert.setParameter("eventId" + row, processed.eventId())
                .setParameter("userId" + row, processed.userId())
                .setParameter("source" + row, processed.source().name())
                .setParameter("status" + row, processed.status().name())
                .setParameter("reasons" + row, REASONS.convertToDatabaseColumn(processed.reasons()))
                .setParameter("processedAt" + row, processed.processedAt());
    }

    private static List<ProcessedUpgrade> toRecords(List<Object[]> rows) {
        List<ProcessedUpgrade> records = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            ProcessedUpgradeEntity entity = (ProcessedUpgradeEntity) row[0];
            records.add(new ProcessedUpgrade(entity.getEventId(), entity.getUserId(), entity.getSource(),
                    entity.getStatus(), entity.getReasons(), (Boolean) row[1], entity.getProcessedAt()));
        }
        return records;
    }
}
