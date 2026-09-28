package com.mercur.upgrade;

import com.mercur.upgrade.messaging.inmemory.InMemoryMessageBroker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full flow over HTTP: ingestion -> in-memory topic -> eligibility -> notification -> storage -> query. */
@SpringBootTest
@AutoConfigureMockMvc
class UpgradeFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryMessageBroker broker;

    @Test
    void batchRequestsAreProcessedAsynchronouslyAndRetrievable() throws Exception {
        mockMvc.perform(post("/api/batch-upgrade").contentType(MediaType.APPLICATION_JSON).content("""
                        [
                          {"userId":"it-ok","userName":"Alice","age":20,"balance":45.50,"parentEmail":"mom@example.com"},
                          {"userId":"it-bad","userName":"","age":30,"balance":10}
                        ]
                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.receipts[0].source").value("BATCH"))
                .andExpect(jsonPath("$.receipts[0].accepted").doesNotExist());

        awaitProcessed("it-ok");
        mockMvc.perform(get("/api/processed-upgrades").param("userId", "it-ok"))
                .andExpect(jsonPath("$[0].status").value("ELIGIBLE"))
                .andExpect(jsonPath("$[0].reasons", hasSize(0)))
                .andExpect(jsonPath("$[0].notificationSent").value(true))
                .andExpect(jsonPath("$[0].processedAt").exists());

        awaitProcessed("it-bad");
        mockMvc.perform(get("/api/processed-upgrades").param("userId", "it-bad"))
                .andExpect(jsonPath("$[0].status").value("INELIGIBLE"))
                .andExpect(jsonPath("$[0].reasons", hasSize(3)))
                .andExpect(jsonPath("$[0].notificationSent").value(true));

        mockMvc.perform(get("/api/notifications"))
                .andExpect(jsonPath("$[?(@.recipient == 'mom@example.com')].role").value("PARENT"));
    }

    @Test
    void realtimeRequestIsAcceptedAndProcessed() throws Exception {
        mockMvc.perform(post("/api/realtime-upgrade").contentType(MediaType.APPLICATION_JSON).content("""
                        {"userId":"it-rt","userName":"Rita","age":23,"balance":30}
                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.source").value("REALTIME"))
                .andExpect(jsonPath("$.eventId").exists());

        awaitProcessed("it-rt");
        mockMvc.perform(get("/api/processed-upgrades").param("userId", "it-rt"))
                .andExpect(jsonPath("$[0].status").value("ELIGIBLE"));
    }

    @Test
    void invalidRequestsAreRejectedWithProblemDetails() throws Exception {
        mockMvc.perform(post("/api/realtime-upgrade").contentType(MediaType.APPLICATION_JSON).content("""
                        {"userName":"NoId","age":20,"balance":50,"parentEmail":"not-an-email"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors", hasSize(2)));

        mockMvc.perform(post("/api/batch-upgrade").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("batch must contain at least one request"));

        mockMvc.perform(post("/api/batch-upgrade").contentType(MediaType.APPLICATION_JSON).content("""
                        [{"userId":"ok","userName":"A","age":20,"balance":50}, {"userName":"missing id"}]
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("[1].userId: userId is required"));

        mockMvc.perform(post("/api/batch-upgrade").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"));

        mockMvc.perform(get("/api/processed-upgrades").param("status", "MAYBE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retriedRequestsWithSameIdempotencyKeyAreProcessedAndNotifiedOnce() throws Exception {
        String body = """
                {"userId":"it-idem","userName":"Ida","age":20,"balance":50,"parentEmail":"ida.parent@example.com"}
                """;
        String first = mockMvc.perform(post("/api/realtime-upgrade").header("Idempotency-Key", "retry-123")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/realtime-upgrade").header("Idempotency-Key", "retry-123")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String batchBody = "[" + body + "]";
        mockMvc.perform(post("/api/batch-upgrade").header("Idempotency-Key", "batch-9")
                .contentType(MediaType.APPLICATION_JSON).content(batchBody)).andExpect(status().isAccepted());
        mockMvc.perform(post("/api/batch-upgrade").header("Idempotency-Key", "batch-9")
                .contentType(MediaType.APPLICATION_JSON).content(batchBody)).andExpect(status().isAccepted());

        assertThat(eventId(first)).isEqualTo(eventId(second));
        await().atMost(Duration.ofSeconds(5)).until(() -> broker.pendingCount() == 0);
        // one real-time + one batch submission, each retried once -> exactly two processed records
        mockMvc.perform(get("/api/processed-upgrades").param("userId", "it-idem"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].source").value("REALTIME"))
                .andExpect(jsonPath("$[1].source").value("BATCH"));
        mockMvc.perform(get("/api/notifications"))
                .andExpect(jsonPath("$[?(@.recipient == 'ida.parent@example.com')]", hasSize(2)));
    }

    @Test
    void invalidIdempotencyKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/realtime-upgrade").header("Idempotency-Key", "has spaces and $ymbols")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"k\",\"userName\":\"K\",\"age\":20,\"balance\":50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
    }

    @Test
    void oversizedBodyIsRejectedBeforeDeserialisation() throws Exception {
        byte[] sixMegabytes = new byte[6 * 1024 * 1024];
        java.util.Arrays.fill(sixMegabytes, (byte) ' ');
        mockMvc.perform(post("/api/batch-upgrade").contentType(MediaType.APPLICATION_JSON).content(sixMegabytes))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.title").value("Payload too large"));
    }

    @Test
    void brokerStartsBeforeAndStopsAfterTheWebServer() {
        assertThat(InMemoryMessageBroker.PHASE)
                .isLessThan(org.springframework.boot.web.server.context.WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE)
                .isLessThan(org.springframework.boot.web.server.context.WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
    }

    private static String eventId(String receiptJson) {
        return com.jayway.jsonpath.JsonPath.read(receiptJson, "$.eventId");
    }

    private void awaitProcessed(String userId) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/api/processed-upgrades").param("userId", userId))
                        .andExpect(jsonPath("$", hasSize(1))));
    }
}
