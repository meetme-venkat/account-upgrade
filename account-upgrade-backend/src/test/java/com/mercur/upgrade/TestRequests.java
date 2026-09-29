package com.mercur.upgrade;

import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.common.UpgradeRequestedEvent;

import java.math.BigDecimal;
import java.time.Instant;

/** Shared test fixtures. */
public final class TestRequests {

    public static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private TestRequests() {
    }

    public static UpgradeRequest eligible() {
        return new UpgradeRequest("u-1", "Alice", 20, new BigDecimal("50.00"), null);
    }

    public static UpgradeRequest eligibleWithParent() {
        return new UpgradeRequest("u-2", "Bob", 18, new BigDecimal("30"), "parent@example.com");
    }

    public static UpgradeRequest request(String userName, Integer age, String balance) {
        return new UpgradeRequest("u-3", userName, age, balance == null ? null : new BigDecimal(balance), null);
    }

    public static UpgradeRequestedEvent event(UpgradeRequest request) {
        return new UpgradeRequestedEvent("evt-" + request.userId(), RequestSource.REALTIME, NOW, request);
    }
}
