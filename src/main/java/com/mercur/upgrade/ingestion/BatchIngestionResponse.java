package com.mercur.upgrade.ingestion;

import java.util.List;

/** Summary of a batch ingestion with one receipt per request, in input order. */
public record BatchIngestionResponse(int total, int accepted, int rejected, List<IngestionReceipt> receipts) {

    public static BatchIngestionResponse of(List<IngestionReceipt> receipts) {
        int accepted = (int) receipts.stream().filter(IngestionReceipt::isAccepted).count();
        return new BatchIngestionResponse(receipts.size(), accepted, receipts.size() - accepted, List.copyOf(receipts));
    }
}
