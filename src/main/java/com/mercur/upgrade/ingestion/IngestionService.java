package com.mercur.upgrade.ingestion;

import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.EventPublisher;
import com.mercur.upgrade.messaging.PublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns inbound requests into {@link UpgradeRequestedEvent}s and publishes them.
 *
 * <p>With an idempotency key the event id is deterministic (real-time: the key; batch: key + item
 * index), so a client retrying after a timeout gets the same event ids and the consumer processes
 * each request only once. Without a key every call creates new events.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final EventPublisher publisher;
    private final Clock clock;

    public IngestionService(EventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public IngestionReceipt ingestRealtime(UpgradeRequest request) {
        return ingestRealtime(request, null);
    }

    /**
     * Publishes a single real-time request.
     *
     * @param idempotencyKey optional client key; {@code null} for none
     * @throws PublishException if the broker rejects the event
     */
    public IngestionReceipt ingestRealtime(UpgradeRequest request, String idempotencyKey) {
        return publish(newEvent(request, RequestSource.REALTIME, idempotencyKey));
    }

    public BatchIngestionResponse ingestBatch(List<UpgradeRequest> requests) {
        return ingestBatch(requests, null);
    }

    /**
     * Publishes every request of a batch. A broker failure for one request does not abort
     * the rest of the batch; it is reported in that request's receipt instead.
     *
     * @param idempotencyKey optional client key for the whole batch; {@code null} for none
     */
    public BatchIngestionResponse ingestBatch(List<UpgradeRequest> requests, String idempotencyKey) {
        List<IngestionReceipt> receipts = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            UpgradeRequest request = requests.get(i);
            String itemKey = idempotencyKey == null ? null : idempotencyKey + ":" + i;
            try {
                receipts.add(publish(newEvent(request, RequestSource.BATCH, itemKey)));
            } catch (PublishException e) {
                log.warn("Batch item for user {} rejected: {}", request.userId(), e.getMessage());
                receipts.add(IngestionReceipt.rejected(request.userId(), RequestSource.BATCH, e.getMessage()));
            }
        }
        return BatchIngestionResponse.of(receipts);
    }

    private UpgradeRequestedEvent newEvent(UpgradeRequest request, RequestSource source, String idempotencyKey) {
        return idempotencyKey == null
                ? UpgradeRequestedEvent.of(request, source, clock.instant())
                : UpgradeRequestedEvent.withIdempotencyKey(idempotencyKey, request, source, clock.instant());
    }

    private IngestionReceipt publish(UpgradeRequestedEvent event) {
        publisher.publish(event);
        return IngestionReceipt.accepted(event.request().userId(), event.eventId(), event.source());
    }
}
