package com.mercur.upgrade.persistence;

import com.mercur.upgrade.common.RequestSource;

import java.time.Instant;
import java.util.List;

/**
 * Stored outcome of one processed upgrade request.
 *
 * @param eventId          id of the processed event (primary key, used for idempotency)
 * @param notificationSent whether every notification for this decision was delivered
 * @param processedAt      time processing completed
 */
public record ProcessedUpgrade(
        String eventId,
        String userId,
        RequestSource source,
        ProcessingStatus status,
        List<String> reasons,
        boolean notificationSent,
        Instant processedAt) {

    public ProcessedUpgrade {
        reasons = List.copyOf(reasons);
    }
}
