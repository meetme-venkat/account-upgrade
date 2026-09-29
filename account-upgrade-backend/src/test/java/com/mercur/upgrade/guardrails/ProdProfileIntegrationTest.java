package com.mercur.upgrade.guardrails;

import com.mercur.upgrade.PostgresContainerSupport;
import com.mercur.upgrade.TestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The prod profile end to end: startup guardrails (a 3-broker cluster, so topics can have RF 3 / minISR 2),
 * guardrail filters, strict input handling and locked-down actuator.
 */
@SpringBootTest(properties = {
        "upgrade.notification.relay.interval=1h",
        "upgrade.messaging.kafka.replication-factor=3",
        "upgrade.messaging.kafka.min-insync-replicas=2",
        "upgrade.guardrails.rate-limit.requests-per-second=1",
        "upgrade.guardrails.rate-limit.burst=2"})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ProdProfileIntegrationTest extends PostgresContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void kafkaCluster(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", TestContainers.kafkaCluster()::bootstrapServers);
    }

    private static MockHttpServletRequestBuilder realtime(String ip, String body) {
        return post("/api/realtime-upgrade").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    @Test
    void addsSecurityHeadersAndARequestId() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void echoesAWellFormedRequestIdAndReplacesAMalformedOne() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "edge-123"))
                .andExpect(header().string("X-Request-Id", "edge-123"));
        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "bad id\r\ninjected"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void rateLimitsEachClient() throws Exception {
        String body = """
                {"userId":"rl-1","userName":"Ann","age":20,"balance":50}""";
        mockMvc.perform(realtime("192.0.2.10", body)).andExpect(status().isAccepted());
        mockMvc.perform(realtime("192.0.2.10", body)).andExpect(status().isAccepted());
        mockMvc.perform(realtime("192.0.2.10", body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void rejectsUnknownFieldsAndOversizedValues() throws Exception {
        mockMvc.perform(realtime("192.0.2.20", """
                        {"userId":"u1","userName":"Ann","age":20,"balance":50,"isAdmin":true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown field 'isAdmin'"));

        mockMvc.perform(realtime("192.0.2.21", """
                        {"userId":"%s","userName":"Ann","age":20,"balance":50}""".formatted("x".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("userId: userId must be at most 64 characters")));
    }

    @Test
    void hidesHealthDetailsAndSensitiveActuatorEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }
}
