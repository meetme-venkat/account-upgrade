package com.mercur.upgrade.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The simulated emails (GET /api/notifications): one per user, parents only for eligible users. */
@Order(6)
@DisplayName("Notifications")
class NotificationTest {

    private static final List<JsonNode> emails = new ArrayList<>();

    @BeforeAll
    static void fetchThisRunsEmails() {
        UpgradeScenario.get().awaitProcessed();
        ApiClient.Response response = Session.UI.get("/api/notifications", Session.auth());
        assertThat(response.status()).isEqualTo(200);
        response.json().forEach(email -> {
            if (email.path("recipient").asText().startsWith(UpgradeScenario.PREFIX + "-")) {
                emails.add(email);
            }
        });
    }

    @Test
    void one_email_per_user() {
        assertThat(emails).filteredOn(email -> email.path("role").asText().equals("USER"))
                .hasSize(UpgradeScenario.DISTINCT_REQUESTS);
    }

    @Test
    void parent_email_only_for_the_eligible_user_with_a_parent() {
        assertThat(emails).filteredOn(email -> email.path("role").asText().equals("PARENT"))
                .extracting(email -> email.path("recipient").asText())
                .containsExactly(UpgradeScenario.userId("alice.parent@example.com"));
    }

    @Test
    void decline_email_lists_the_reason() {
        assertThat(emails).filteredOn(email -> email.path("recipient").asText().equals(UpgradeScenario.userId("poor")))
                .singleElement()
                .satisfies(email -> assertThat(email.path("body").asText()).contains("at least $30"));
    }
}
