package com.mercur.upgrade.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The batch and real-time endpoints accept requests for asynchronous processing (202). */
@Order(3)
@DisplayName("Ingestion")
class IngestionTest {

    @Test
    void batch_of_7_is_accepted() {
        UpgradeScenario scenario = UpgradeScenario.get();
        assertThat(scenario.batch.status()).isEqualTo(202);
        assertThat(scenario.batch.json().path("accepted").asInt()).isEqualTo(7);
    }

    @Test
    void real_time_request_is_accepted() {
        assertThat(UpgradeScenario.get().realtime.status()).isEqualTo(202);
    }

    @Test
    void request_retried_with_the_same_Idempotency_Key_is_accepted_each_time() {
        assertThat(UpgradeScenario.get().idempotent).extracting(ApiClient.Response::status).containsOnly(202);
    }
}
