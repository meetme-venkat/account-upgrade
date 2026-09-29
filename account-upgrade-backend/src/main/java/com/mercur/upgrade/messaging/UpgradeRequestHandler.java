package com.mercur.upgrade.messaging;

import com.mercur.upgrade.common.UpgradeRequestedEvent;

/**
 * Consumer side of the {@code upgrade-requests} topic. The broker adapters deliver
 * every event to this handler; throwing an exception triggers the broker's retry policy.
 */
public interface UpgradeRequestHandler {

    void handle(UpgradeRequestedEvent event);
}
