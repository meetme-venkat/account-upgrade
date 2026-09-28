package com.mercur.upgrade.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Event published to the {@code upgrade-requests} topic for every ingested request.
 *
 * @param eventId    unique id used for idempotent processing (at-least-once delivery)
 * @param source     ingestion channel
 * @param receivedAt time the request was accepted by the ingestion API
 * @param request    the request payload
 */
public record UpgradeRequestedEvent(String eventId, RequestSource source, Instant receivedAt, UpgradeRequest request) {

    public UpgradeRequestedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(request, "request");
    }

    public static UpgradeRequestedEvent of(UpgradeRequest request, RequestSource source, Instant receivedAt) {
        return new UpgradeRequestedEvent(UUID.randomUUID().toString(), source, receivedAt, request);
    }

    /**
     * Creates an event whose id is derived from a client-supplied idempotency key, so a retried
     * HTTP request yields the same event id and is de-duplicated by the consumer.
     */
    public static UpgradeRequestedEvent withIdempotencyKey(String idempotencyKey, UpgradeRequest request,
                                                           RequestSource source, Instant receivedAt) {
        String eventId = UUID.nameUUIDFromBytes((source + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8)).toString();
        return new UpgradeRequestedEvent(eventId, source, receivedAt, request);
    }

    /** Partition/message key: keeps all events of one user in order. */
    public String key() {
        return request.userId();
    }
}
