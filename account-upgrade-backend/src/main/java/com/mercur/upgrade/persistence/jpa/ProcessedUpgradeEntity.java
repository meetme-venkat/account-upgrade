package com.mercur.upgrade.persistence.jpa;

import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.persistence.ProcessingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/**
 * A row of {@code processed_upgrades}. Written with native SQL (ProcessedUpgradeRepositoryImpl: duplicate-safe
 * inserts), read with JPQL.
 */
@Entity
@Table(name = "processed_upgrades")
public class ProcessedUpgradeEntity {

    @Id
    @Column(name = "event_id")
    private String eventId;

    /** Store order, assigned by the database. */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RequestSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProcessingStatus status;

    @Convert(converter = ReasonsConverter.class)
    @Column(name = "reasons", nullable = false)
    private List<String> reasons;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedUpgradeEntity() {
    }

    public String getEventId() {
        return eventId;
    }

    public Long getSeq() {
        return seq;
    }

    public String getUserId() {
        return userId;
    }

    public RequestSource getSource() {
        return source;
    }

    public ProcessingStatus getStatus() {
        return status;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
