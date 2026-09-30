package com.mercur.upgrade.e2e;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;

/**
 * The upgrade requests of one run, submitted once and shared by the ingestion, processing and notification tests.
 * User ids start with a per-run prefix, so runs never see each other's data (the records stay, like any request).
 *
 * <pre>
 *   batch      alice  19, $120.50, parent email   ELIGIBLE (parent notified too)
 *              age18  18, $30                     ELIGIBLE (both lower bounds)
 *              age23  23, $30                     ELIGIBLE (upper age bound)
 *              age17  17, $50                     INELIGIBLE
 *              age24  24, $50                     INELIGIBLE
 *              poor   20, $29.99, parent email    INELIGIBLE (parent not notified)
 *              noname "", 17, $12.75              INELIGIBLE, 3 reasons
 *   real-time  rt     21, $45                     ELIGIBLE
 *              idem   21, $80, same Idempotency-Key 3 times: stored once
 * </pre>
 */
final class UpgradeScenario {

    static final String PREFIX = "e2e-" + System.currentTimeMillis();
    static final int DISTINCT_REQUESTS = 9;

    private static UpgradeScenario instance;

    final ApiClient.Response batch;
    final ApiClient.Response realtime;
    final List<ApiClient.Response> idempotent = new ArrayList<>();
    private Map<String, List<JsonNode>> processed;

    private UpgradeScenario() {
        List<Map<String, Object>> requests = List.of(
                request("alice", "Alice", 19, 120.5, PREFIX + "-alice.parent@example.com"),
                request("age18", "Min", 18, 30, null),
                request("age23", "Max", 23, 30, null),
                request("age17", "Young", 17, 50, null),
                request("age24", "Old", 24, 50, null),
                request("poor", "Poor", 20, 29.99, PREFIX + "-poor.parent@example.com"),
                request("noname", "", 17, 12.75, null));
        batch = Session.UI.post("/api/batch-upgrade", requests, Session.auth());
        realtime = Session.UI.post("/api/realtime-upgrade", request("rt", "Dana", 21, 45, null), Session.auth());
        Map<String, String> headers = new HashMap<>(Session.auth());
        headers.put("Idempotency-Key", PREFIX + "-key");
        for (int attempt = 0; attempt < 3; attempt++) {
            idempotent.add(Session.UI.post("/api/realtime-upgrade", request("idem", "Ivy", 21, 80, null), headers));
        }
    }

    static synchronized UpgradeScenario get() {
        if (instance == null) {
            instance = new UpgradeScenario();
        }
        return instance;
    }

    static String userId(String suffix) {
        return PREFIX + "-" + suffix;
    }

    /**
     * This run's processed requests by user id (all of their rows, to count duplicates), once every one of them is
     * processed and notified: Kafka, the eligibility rules, PostgreSQL and the email outbox all worked.
     */
    synchronized Map<String, List<JsonNode>> awaitProcessed() {
        if (processed != null) {
            return processed;
        }
        Map<String, List<JsonNode>> rows = new LinkedHashMap<>();
        await("all " + DISTINCT_REQUESTS + " requests processed and notified")
                .atMost(E2eSettings.PROCESSING_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    rows.clear();
                    for (JsonNode row : processedUpgrades("")) {
                        String userId = row.path("userId").asText();
                        if (userId.startsWith(PREFIX + "-")) {
                            rows.computeIfAbsent(userId, id -> new ArrayList<>()).add(row);
                        }
                    }
                    return rows.values().stream().flatMap(List::stream)
                            .filter(row -> row.path("notificationSent").asBoolean()).count() >= DISTINCT_REQUESTS;
                });
        processed = rows;
        return processed;
    }

    /** GET /api/processed-upgrades with the given filter ("" or "&status=..."), as a list. */
    static List<JsonNode> processedUpgrades(String filter) {
        ApiClient.Response response = Session.UI.get("/api/processed-upgrades?limit=5000" + filter, Session.auth());
        List<JsonNode> rows = new ArrayList<>();
        if (response.status() == 200 && response.json() != null) {
            response.json().forEach(rows::add);
        }
        return rows;
    }

    private static Map<String, Object> request(String suffix, String userName, int age, double balance,
                                               String parentEmail) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", userId(suffix));
        request.put("userName", userName);
        request.put("age", age);
        request.put("balance", balance);
        if (parentEmail != null) {
            request.put("parentEmail", parentEmail);
        }
        return request;
    }
}
