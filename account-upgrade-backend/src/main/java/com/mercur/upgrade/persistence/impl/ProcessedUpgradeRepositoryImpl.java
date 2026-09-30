package com.mercur.upgrade.persistence.impl;

import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.persistence.ProcessingStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL store shared by all instances.
 *
 * <ul>
 *   <li>{@link #saveIfAbsent} is a single {@code INSERT ... ON CONFLICT DO NOTHING}: when two instances
 *       race on one event, the second blocks until the first commits and then inserts nothing.</li>
 *   <li>{@code notificationSent} is derived when reading, from the notification outbox: it is true once
 *       every notification of the event has been delivered by the outbox relay. The flag passed to
 *       {@link #saveIfAbsent} is therefore ignored.</li>
 * </ul>
 */
@Repository
public class ProcessedUpgradeRepositoryImpl implements ProcessedUpgradeRepository {

    private static final String SELECT = """
            SELECT p.seq, p.event_id, p.user_id, p.source, p.status, p.reasons, p.processed_at,
                   NOT EXISTS (SELECT 1 FROM notification_outbox o
                               WHERE o.event_id = p.event_id AND o.sent_at IS NULL) AS notification_sent
            FROM processed_upgrades p
            """;

    /** Rows per multi-row statement: well below PostgreSQL's 65,535 bind parameters (6 per row). */
    static final int MAX_ROWS_PER_STATEMENT = 1000;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final RowMapper<ProcessedUpgrade> rowMapper;

    public ProcessedUpgradeRepositoryImpl(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.rowMapper = (rs, rowNum) -> new ProcessedUpgrade(
                rs.getString("event_id"),
                rs.getString("user_id"),
                RequestSource.valueOf(rs.getString("source")),
                ProcessingStatus.valueOf(rs.getString("status")),
                List.of(jsonMapper.readValue(rs.getString("reasons"), String[].class)),
                rs.getBoolean("notification_sent"),
                rs.getObject("processed_at", OffsetDateTime.class).toInstant());
    }

    @Override
    public boolean saveIfAbsent(ProcessedUpgrade processed) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_upgrades (event_id, user_id, source, status, reasons, processed_at)
                        VALUES (:eventId, :userId, :source, :status, :reasons, :processedAt)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("eventId", processed.eventId())
                .param("userId", processed.userId())
                .param("source", processed.source().name())
                .param("status", processed.status().name())
                .param("reasons", jsonMapper.writeValueAsString(processed.reasons()))
                .param("processedAt", OffsetDateTime.ofInstant(processed.processedAt(), ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

    /**
     * One multi-row {@code INSERT ... ON CONFLICT DO NOTHING RETURNING event_id} per {@value #MAX_ROWS_PER_STATEMENT}
     * records. PostgreSQL assigns {@code seq} in the order of the {@code VALUES} rows, so the list order is the store
     * order. A record whose event id exists, or is being inserted by another transaction that then commits, is
     * skipped and not returned.
     */
    @Override
    public Set<String> saveAllIfAbsent(List<ProcessedUpgrade> processedUpgrades) {
        Set<String> stored = new LinkedHashSet<>();
        for (int from = 0; from < processedUpgrades.size(); from += MAX_ROWS_PER_STATEMENT) {
            List<ProcessedUpgrade> rows =
                    processedUpgrades.subList(from, Math.min(from + MAX_ROWS_PER_STATEMENT, processedUpgrades.size()));
            StringBuilder sql = new StringBuilder("INSERT INTO processed_upgrades "
                    + "(event_id, user_id, source, status, reasons, processed_at) VALUES ");
            Map<String, Object> params = new HashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                ProcessedUpgrade row = rows.get(i);
                sql.append(i == 0 ? "" : ", ")
                        .append("(:eventId%1$d, :userId%1$d, :source%1$d, :status%1$d, :reasons%1$d, :processedAt%1$d)"
                                .formatted(i));
                params.put("eventId" + i, row.eventId());
                params.put("userId" + i, row.userId());
                params.put("source" + i, row.source().name());
                params.put("status" + i, row.status().name());
                params.put("reasons" + i, jsonMapper.writeValueAsString(row.reasons()));
                params.put("processedAt" + i, OffsetDateTime.ofInstant(row.processedAt(), ZoneOffset.UTC));
            }
            sql.append(" ON CONFLICT (event_id) DO NOTHING RETURNING event_id");
            stored.addAll(jdbc.sql(sql.toString()).params(params).query(String.class).list());
        }
        return stored;
    }

    @Override
    public Set<String> findStoredEventIds(Collection<String> eventIds) {
        List<String> ids = List.copyOf(eventIds);
        Set<String> stored = new HashSet<>();
        for (int from = 0; from < ids.size(); from += MAX_ROWS_PER_STATEMENT) {
            stored.addAll(jdbc.sql("SELECT event_id FROM processed_upgrades WHERE event_id IN (:eventIds)")
                    .param("eventIds", ids.subList(from, Math.min(from + MAX_ROWS_PER_STATEMENT, ids.size())))
                    .query(String.class)
                    .list());
        }
        return stored;
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_upgrades WHERE event_id = :eventId)")
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT count(*) FROM processed_upgrades").query(Long.class).single();
    }

    @Override
    public List<ProcessedUpgrade> findAll() {
        return jdbc.sql(SELECT + "ORDER BY p.seq").query(rowMapper).list();
    }

    @Override
    public List<ProcessedUpgrade> find(ProcessingStatus status, String userId, int limit) {
        // Filters are appended only when set: a parameter used just in "IS NULL" has no inferable type in PostgreSQL.
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (status != null) {
            conditions.add("p.status = :status");
            params.put("status", status.name());
        }
        if (userId != null) {
            conditions.add("p.user_id = :userId");
            params.put("userId", userId);
        }
        params.put("limit", limit);
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + " ";
        return jdbc.sql("SELECT * FROM (" + SELECT + where + "ORDER BY p.seq DESC LIMIT :limit) newest ORDER BY seq")
                .params(params)
                .query(rowMapper)
                .list();
    }
}
