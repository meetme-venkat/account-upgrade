package com.mercur.upgrade.e2e;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** One login as the administrator, shared by every test class of a run. */
final class Session {

    static final ApiClient UI = new ApiClient(E2eSettings.BASE_URL);
    static final ApiClient API = new ApiClient(E2eSettings.API_URL);

    private static String token;

    private Session() {
    }

    static synchronized ApiClient.Response login(String password) {
        return UI.post("/api/auth/login",
                Map.of("username", E2eSettings.ADMIN_USERNAME, "password", password), Map.of());
    }

    /** The administrator's access token; fails every test that needs it if login does not work. */
    static synchronized String token() {
        if (token == null) {
            ApiClient.Response login = login(E2eSettings.ADMIN_PASSWORD);
            assertThat(login.status()).as("login as %s", E2eSettings.ADMIN_USERNAME).isEqualTo(200);
            assertThat(login.json().path("tokenType").asText()).isEqualTo("Bearer");
            token = login.json().path("accessToken").asText();
            assertThat(token).isNotBlank();
        }
        return token;
    }

    static Map<String, String> auth() {
        return Map.of("Authorization", "Bearer " + token());
    }
}
