package com.mercur.upgrade.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.mercur.upgrade.common.RequestSource;

/**
 * Acknowledgement for one ingested request.
 *
 * @param eventId id of the published event ({@code null} when rejected)
 * @param error   why the request was rejected ({@code null} when accepted)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestionReceipt(String userId, String eventId, RequestSource source, Status status, String error) {

    public enum Status {
        ACCEPTED,
        REJECTED
    }

    public static IngestionReceipt accepted(String userId, String eventId, RequestSource source) {
        return new IngestionReceipt(userId, eventId, source, Status.ACCEPTED, null);
    }

    public static IngestionReceipt rejected(String userId, RequestSource source, String error) {
        return new IngestionReceipt(userId, null, source, Status.REJECTED, error);
    }

    @JsonIgnore
    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }
}
