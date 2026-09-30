package com.mercur.upgrade.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The requests went through Kafka, the eligibility rules, PostgreSQL and the email outbox. */
@Order(5)
@DisplayName("Processing (Kafka, eligibility, PostgreSQL, outbox)")
class ProcessingTest {

    private static Map<String, List<JsonNode>> processed;

    @BeforeAll
    static void waitForProcessing() {
        processed = UpgradeScenario.get().awaitProcessed();
    }

    @Test
    void every_distinct_request_is_processed_and_notified() {
        assertThat(processed).hasSize(UpgradeScenario.DISTINCT_REQUESTS);
        assertThat(processed.values()).allSatisfy(rows ->
                assertThat(rows).allSatisfy(row -> assertThat(row.path("notificationSent").asBoolean()).isTrue()));
    }

    @ParameterizedTest(name = "{1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            alice | age 19, $120.50, parent email       | ELIGIBLE
            age18 | age 18 (lower bound), $30 (minimum) | ELIGIBLE
            age23 | age 23 (upper bound)                | ELIGIBLE
            age17 | age 17                              | INELIGIBLE
            age24 | age 24                              | INELIGIBLE
            poor  | balance $29.99                      | INELIGIBLE
            rt    | real-time: age 21, $45              | ELIGIBLE
            """)
    void eligibility_rules_decide_the_status(String user, String description, String status) {
        assertThat(row(user).path("status").asText()).as(description).isEqualTo(status);
    }

    @Test
    void blank_name_underage_and_low_balance_records_every_reason() {
        assertThat(row("noname").path("status").asText()).isEqualTo("INELIGIBLE");
        assertThat(row("noname").path("reasons")).hasSize(3);
    }

    @Test
    void Idempotency_Key_sent_3_times_is_stored_once() {
        assertThat(processed.get(UpgradeScenario.userId("idem"))).hasSize(1);
    }

    @Test
    void filter_by_status() {
        long ineligible = UpgradeScenario.processedUpgrades("&status=INELIGIBLE").stream()
                .filter(row -> row.path("userId").asText().startsWith(UpgradeScenario.PREFIX + "-"))
                .count();
        assertThat(ineligible).isEqualTo(4);
    }

    @Test
    void filter_by_userId() {
        String userId = UpgradeScenario.userId("alice");
        ApiClient.Response response = Session.UI.get("/api/processed-upgrades?userId=" + userId, Session.auth());
        assertThat(response.json()).hasSize(1);
        assertThat(response.json().get(0).path("userId").asText()).isEqualTo(userId);
    }

    private static JsonNode row(String user) {
        List<JsonNode> rows = processed.get(UpgradeScenario.userId(user));
        assertThat(rows).as("processed rows of %s", user).isNotEmpty();
        return rows.get(0);
    }
}
