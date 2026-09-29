package com.mercur.upgrade.web;

import com.mercur.upgrade.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS is off unless origins are configured, so a separately hosted frontend must be allowed explicitly. Preflight
 * requests carry no token: they are answered before authentication.
 */
class CorsConfigTest {

    private static final String FRONTEND = "http://localhost:4200";

    @Nested
    @AutoConfigureMockMvc
    class Default extends IntegrationTestSupport {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void doesNotGrantCrossOriginAccess() throws Exception {
            mockMvc.perform(preflight()).andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Nested
    @SpringBootTest(properties = {"upgrade.web.cors.allowed-origins=" + FRONTEND, "upgrade.notification.relay.interval=1h"})
    @AutoConfigureMockMvc
    class Configured extends IntegrationTestSupport {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void allowsPreflightFromConfiguredOriginIncludingIdempotencyKeyAndAuthorization() throws Exception {
            mockMvc.perform(preflight())
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                            "Content-Type, Idempotency-Key, Authorization"));
        }
    }

    private static org.springframework.test.web.servlet.RequestBuilder preflight() {
        return options("/api/realtime-upgrade")
                .header(HttpHeaders.ORIGIN, FRONTEND)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, Idempotency-Key, Authorization");
    }
}
