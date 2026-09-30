package com.mercur.upgrade.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

/** Spring Data access to {@code processed_upgrades}; used only by ProcessedUpgradeRepositoryImpl. */
public interface ProcessedUpgradeJpaRepository extends JpaRepository<ProcessedUpgradeEntity, String> {

    @Query("select p.eventId from ProcessedUpgradeEntity p where p.eventId in :eventIds")
    List<String> findStoredEventIds(Collection<String> eventIds);
}
