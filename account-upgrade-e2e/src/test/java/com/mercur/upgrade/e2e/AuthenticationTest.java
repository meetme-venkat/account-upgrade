package com.mercur.upgrade.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every /api call needs a valid access token from POST /api/auth/login; health stays public. */
@Order(2)
@DisplayName("Authentication")
class AuthenticationTest {

    private static final String ONE_ROW = "/api/processed-upgrades?limit=1";

    @Test
    void API_without_a_token_is_refused() {
        assertThat(Session.UI.get("/api/processed-upgrades").status()).isEqualTo(401);
    }

    @Test
    void login_with_a_wrong_password_is_refused() {
        assertThat(Session.login(E2eSettings.ADMIN_PASSWORD + "-wrong").status()).isEqualTo(401);
    }

    @Test
    void login_returns_a_bearer_token_that_opens_the_API() {
        assertThat(Session.UI.get(ONE_ROW, Session.auth()).status()).isEqualTo(200);
    }

    @Test
    void tampered_token_is_refused() {
        String token = Session.token();
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThat(Session.UI.get(ONE_ROW, Map.of("Authorization", "Bearer " + tampered)).status()).isEqualTo(401);
    }

    @Test
    void health_stays_public() {
        assertThat(Session.UI.get("/actuator/health").status()).isEqualTo(200);
    }
}
