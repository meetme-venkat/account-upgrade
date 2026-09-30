package com.mercur.upgrade.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Security headers on the UI, and the backend's rate limit. Runs last: the burst briefly throttles this machine. */
@Order(7)
@DisplayName("Guardrails")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuardrailsTest {

    private static ApiClient.Response signInPage;

    @BeforeAll
    static void fetchSignInPage() {
        signInPage = Session.UI.get("/login");
    }

    @Test
    @Order(1)
    void UI_sign_in_page_is_served() {
        assertThat(signInPage.status()).isEqualTo(200);
    }

    @Order(2)
    @ParameterizedTest(name = "UI sends {0}")
    @ValueSource(strings = {"Content-Security-Policy", "X-Frame-Options", "X-Content-Type-Options", "Referrer-Policy"})
    void UI_sends_security_header(String header) {
        assertThat(signInPage.headers().firstValue(header)).isPresent();
    }

    /**
     * 200 concurrent requests without a token, straight to the backend: each replica (burst 40, its own bucket)
     * answers some with 429 before authentication runs, and the rest with 401. Leave out with
     * -DexcludedGroups=rate-limit.
     */
    @Test
    @Order(3)
    @Tag("rate-limit")
    void rate_limit_throttles_a_burst_before_authentication() {
        List<CompletableFuture<Integer>> calls = IntStream.range(0, 200)
                .mapToObj(i -> Session.API.getAsync("/api/processed-upgrades"))
                .toList();
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
        assertThat(statuses).contains(429).containsOnly(429, 401);
    }
}
