package com.mercur.upgrade.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Invalid input is rejected with 400 before it reaches Kafka. */
@Order(4)
@DisplayName("Validation errors")
class ValidationTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            missing userId               | /api/realtime-upgrade | {"userName":"x","age":20,"balance":50}
            invalid parentEmail          | /api/realtime-upgrade | {"userId":"z","age":20,"balance":50,"parentEmail":"nope"}
            empty batch                  | /api/batch-upgrade    | []
            malformed JSON               | /api/realtime-upgrade | {oops
            unknown field (strict input) | /api/realtime-upgrade | {"userId":"z","age":20,"balance":50,"isAdmin":true}
            """)
    void invalid_request_is_rejected(String description, String path, String body) {
        assertThat(Session.UI.post(path, body, Session.auth()).status()).as(description).isEqualTo(400);
    }
}
