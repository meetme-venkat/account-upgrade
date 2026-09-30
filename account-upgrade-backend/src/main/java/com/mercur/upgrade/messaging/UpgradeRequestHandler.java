package com.mercur.upgrade.messaging;

import com.mercur.upgrade.common.UpgradeRequestedEvent;

import java.util.List;

/**
 * Consumer side of the {@code upgrade-requests} topic. The broker adapters deliver
 * every event to this handler; throwing an exception triggers the broker's retry policy.
 */
public interface UpgradeRequestHandler {

    void handle(UpgradeRequestedEvent event);

    /**
     * Processes the events in order, in one transaction: their decisions and notifications are written together,
     * with a few statements instead of a few per event.
     *
     * <p>All or nothing: if any event fails, nothing of the batch is stored and the exception is thrown. The broker
     * adapter then processes the events one at a time with {@link #handle}, to find the one that fails.
     */
    void handleAll(List<UpgradeRequestedEvent> events);
}
