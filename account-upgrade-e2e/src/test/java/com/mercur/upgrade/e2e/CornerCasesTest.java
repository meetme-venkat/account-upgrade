package com.mercur.upgrade.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Live corner cases against a running deployment, straight to the backend's API: input validation, eligibility
 * boundaries, notifications, duplicates and ordering, a load burst, and the query filters. Results go to
 * test-results/corner-cases.md (one row per case, overwritten on every run).
 *
 * <p>Not part of the default run (nor the pipeline's): {@code mvnw verify -Pcorner-cases}. Needs kubectl access to the
 * deployment (counts are read from PostgreSQL). Each run uses its own user-id prefix and checks only its own records,
 * so it can run against a deployment with any history. Throttled calls (429) are retried after their Retry-After.
 * Adds about 5,400 requests to the database per run.
 */
@Tag("corner-cases")
@DisplayName("Corner cases")
@ExtendWith(MarkdownReport.class)
class CornerCasesTest {

    static final String P = "cc-" + System.currentTimeMillis();
    static final ApiClient API = new ApiClient(E2eSettings.API_URL, true);
    static final Duration LOAD_TIMEOUT =
            Duration.ofSeconds(Long.parseLong(E2eSettings.setting("e2e.loadTimeout", "E2E_LOAD_TIMEOUT", "180")));

    static String id(String suffix) {
        return P + "-" + suffix;
    }

    /** A JSON template with {P} standing for this run's prefix. */
    static String json(String template) {
        return template.replace("{P}", P);
    }

    // ------------------------------------------------------------------------------------------------ 1. Validation

    record Invalid(String name, String method, String path, String body, String contentType, int status, String contains) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Invalid> invalidRequests() {
        String realtime = "/api/realtime-upgrade";
        String batch = "/api/batch-upgrade";
        String json = "application/json";
        return List.of(
                new Invalid("Missing userId", "POST", realtime, "{\"userName\":\"A\",\"age\":20,\"balance\":50}", json, 400, "userId is required"),
                new Invalid("Blank userId \"   \"", "POST", realtime, "{\"userId\":\"   \",\"userName\":\"A\",\"age\":20,\"balance\":50}", json, 400, "userId is required"),
                new Invalid("Invalid parentEmail", "POST", realtime, json("{\"userId\":\"{P}-v1\",\"userName\":\"A\",\"age\":20,\"balance\":50,\"parentEmail\":\"not-an-email\"}"), json, 400, "valid email"),
                new Invalid("Malformed JSON", "POST", realtime, "{\"userId\":", json, 400, "Malformed request"),
                new Invalid("Empty body", "POST", realtime, "", json, 400, "Malformed request"),
                new Invalid("Age not a number", "POST", realtime, json("{\"userId\":\"{P}-v2\",\"userName\":\"A\",\"age\":\"abc\",\"balance\":50}"), json, 400, "Malformed request"),
                new Invalid("Balance not a number", "POST", realtime, json("{\"userId\":\"{P}-v3\",\"userName\":\"A\",\"age\":20,\"balance\":\"lots\"}"), json, 400, "Malformed request"),
                new Invalid("Array sent to realtime", "POST", realtime, json("[{\"userId\":\"{P}-v4\"}]"), json, 400, null),
                new Invalid("Object sent to batch", "POST", batch, json("{\"userId\":\"{P}-v5\"}"), json, 400, null),
                new Invalid("Empty batch []", "POST", batch, "[]", json, 400, "at least one request"),
                new Invalid("Batch with invalid 2nd item", "POST", batch, json("[{\"userId\":\"{P}-v6\",\"userName\":\"A\",\"age\":20,\"balance\":50},{\"userName\":\"no id\"}]"), json, 400, "[1].userId"),
                new Invalid("Wrong content type (text/plain)", "POST", realtime, "hello", "text/plain", 415, null),
                new Invalid("Wrong method (GET on POST endpoint)", "GET", realtime, null, null, 405, null),
                new Invalid("Unknown path", "GET", "/api/does-not-exist", null, null, 404, null),
                new Invalid("Invalid status filter", "GET", "/api/processed-upgrades?status=MAYBE", null, null, 400, "Invalid value"),
                new Invalid("Lower-case status filter", "GET", "/api/processed-upgrades?status=eligible", null, null, 400, null),
                // The prod profile's strict input (the old in-memory version ignored unknown fields and answered 202).
                new Invalid("Unknown JSON field rejected (strict input)", "POST", realtime, json("{\"userId\":\"{P}-c-unknown\",\"userName\":\"A\",\"age\":20,\"balance\":50,\"favouriteColour\":\"blue\"}"), json, 400, null));
    }

    @Nested
    @Order(1)
    @DisplayName("1. Validation")
    class Validation {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.mercur.upgrade.e2e.CornerCasesTest#invalidRequests")
        void is_rejected(Invalid request) {
            ApiClient.Response response = API.send(request.method(), request.path(), request.body(),
                    request.contentType(), Session.auth());
            MarkdownReport.observe(response.summary());
            assertThat(response.status()).isEqualTo(request.status());
            if (request.contains() != null) {
                assertThat(response.body()).contains(request.contains());
            }
        }

        @Test
        @DisplayName("Batch over 10,000 items")
        void batch_over_the_limit() {
            String items = IntStream.rangeClosed(1, 10_001)
                    .mapToObj(i -> "{\"userId\":\"" + id("big" + i) + "\",\"userName\":\"B\",\"age\":20,\"balance\":50}")
                    .collect(Collectors.joining(",", "[", "]"));
            ApiClient.Response response = API.post("/api/batch-upgrade", items, Session.auth());
            MarkdownReport.observe(response.summary());
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("must not exceed 10000");
        }
    }

    // ----------------------------------------------------------------------------------------------- 2. Eligibility

    record Eligibility(String user, String json, String status, int reasons, String fragment) {
        @Override
        public String toString() {
            return user;
        }
    }

    static List<Eligibility> eligibilityCases() {
        return List.of(
                new Eligibility("c-age17", "{\"userId\":\"{P}-c-age17\",\"userName\":\"A\",\"age\":17,\"balance\":50}", "INELIGIBLE", 1, "but was 17"),
                new Eligibility("c-age18", "{\"userId\":\"{P}-c-age18\",\"userName\":\"A\",\"age\":18,\"balance\":50}", "ELIGIBLE", 0, null),
                new Eligibility("c-age23", "{\"userId\":\"{P}-c-age23\",\"userName\":\"A\",\"age\":23,\"balance\":50}", "ELIGIBLE", 0, null),
                new Eligibility("c-age24", "{\"userId\":\"{P}-c-age24\",\"userName\":\"A\",\"age\":24,\"balance\":50}", "INELIGIBLE", 1, "but was 24"),
                new Eligibility("c-age0", "{\"userId\":\"{P}-c-age0\",\"userName\":\"A\",\"age\":0,\"balance\":50}", "INELIGIBLE", 1, "but was 0"),
                new Eligibility("c-ageneg", "{\"userId\":\"{P}-c-ageneg\",\"userName\":\"A\",\"age\":-5,\"balance\":50}", "INELIGIBLE", 1, "but was -5"),
                new Eligibility("c-age200", "{\"userId\":\"{P}-c-age200\",\"userName\":\"A\",\"age\":200,\"balance\":50}", "INELIGIBLE", 1, "but was 200"),
                new Eligibility("c-agenull", "{\"userId\":\"{P}-c-agenull\",\"userName\":\"A\",\"balance\":50}", "INELIGIBLE", 1, "Age is required"),
                new Eligibility("c-agefloat", "{\"userId\":\"{P}-c-agefloat\",\"userName\":\"A\",\"age\":20.9,\"balance\":50}", "ELIGIBLE", 0, null),
                new Eligibility("c-bal2999", "{\"userId\":\"{P}-c-bal2999\",\"userName\":\"A\",\"age\":20,\"balance\":29.99}", "INELIGIBLE", 1, "was $29.99"),
                new Eligibility("c-bal29999", "{\"userId\":\"{P}-c-bal29999\",\"userName\":\"A\",\"age\":20,\"balance\":29.999}", "INELIGIBLE", 1, "was $29.999"),
                new Eligibility("c-bal30", "{\"userId\":\"{P}-c-bal30\",\"userName\":\"A\",\"age\":20,\"balance\":30}", "ELIGIBLE", 0, null),
                new Eligibility("c-bal3000", "{\"userId\":\"{P}-c-bal3000\",\"userName\":\"A\",\"age\":20,\"balance\":30.00}", "ELIGIBLE", 0, null),
                new Eligibility("c-bal0", "{\"userId\":\"{P}-c-bal0\",\"userName\":\"A\",\"age\":20,\"balance\":0}", "INELIGIBLE", 1, "was $0"),
                new Eligibility("c-balneg", "{\"userId\":\"{P}-c-balneg\",\"userName\":\"A\",\"age\":20,\"balance\":-100}", "INELIGIBLE", 1, "was $-100"),
                new Eligibility("c-balhuge", "{\"userId\":\"{P}-c-balhuge\",\"userName\":\"A\",\"age\":20,\"balance\":999999999999.99}", "ELIGIBLE", 0, null),
                new Eligibility("c-balstr", "{\"userId\":\"{P}-c-balstr\",\"userName\":\"A\",\"age\":20,\"balance\":\"45.5\"}", "ELIGIBLE", 0, null),
                new Eligibility("c-balnull", "{\"userId\":\"{P}-c-balnull\",\"userName\":\"A\",\"age\":20}", "INELIGIBLE", 1, "Balance is required"),
                new Eligibility("c-nameempty", "{\"userId\":\"{P}-c-nameempty\",\"userName\":\"\",\"age\":20,\"balance\":50}", "INELIGIBLE", 1, "User name must not be empty"),
                new Eligibility("c-nameblank", "{\"userId\":\"{P}-c-nameblank\",\"userName\":\"   \",\"age\":20,\"balance\":50}", "INELIGIBLE", 1, "User name must not be empty"),
                new Eligibility("c-namenull", "{\"userId\":\"{P}-c-namenull\",\"age\":20,\"balance\":50}", "INELIGIBLE", 1, "User name must not be empty"),
                new Eligibility("c-allfail", "{\"userId\":\"{P}-c-allfail\",\"userName\":\"\",\"age\":99,\"balance\":1}", "INELIGIBLE", 3, null),
                new Eligibility("c-onlyid", "{\"userId\":\"{P}-c-onlyid\"}", "INELIGIBLE", 3, "required"),
                new Eligibility("c-unicode", "{\"userId\":\"{P}-c-unicode\",\"userName\":\"Zoë Łukasz 名\",\"age\":21,\"balance\":31}", "ELIGIBLE", 0, null));
    }

    @Nested
    @Order(2)
    @DisplayName("2. Eligibility")
    class EligibilityRules {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.mercur.upgrade.e2e.CornerCasesTest#eligibilityCases")
        void is_decided(Eligibility request) {
            Submitted submitted = Submitted.get();
            assertThat(submitted.ingestion.get(request.user()).status()).as("ingestion").isEqualTo(202);
            JsonNode record = submitted.record(request.user());
            List<String> reasons = texts(record.path("reasons"));
            MarkdownReport.observe(record.path("status").asText() + "; " + (reasons.isEmpty() ? "-" : String.join(" | ", reasons)));
            assertThat(record.path("status").asText()).isEqualTo(request.status());
            assertThat(reasons).hasSize(request.reasons());
            assertThat(record.path("notificationSent").asBoolean()).isTrue();
            assertThat(record.path("processedAt").asText()).isNotBlank();
            if (request.fragment() != null) {
                assertThat(String.join(" | ", reasons)).contains(request.fragment());
            }
        }
    }

    // --------------------------------------------------------------------------------------------- 3. Notifications

    record Notified(String user, String json, boolean parentNotified) {
        @Override
        public String toString() {
            return user;
        }
    }

    static List<Notified> notificationCases() {
        return List.of(
                new Notified("n-elig-parent", "{\"userId\":\"{P}-n-elig-parent\",\"userName\":\"P\",\"age\":20,\"balance\":50,\"parentEmail\":\"p1@example.com\"}", true),
                new Notified("n-elig-noparent", "{\"userId\":\"{P}-n-elig-noparent\",\"userName\":\"P\",\"age\":20,\"balance\":50}", false),
                new Notified("n-elig-nullpar", "{\"userId\":\"{P}-n-elig-nullpar\",\"userName\":\"P\",\"age\":20,\"balance\":50,\"parentEmail\":null}", false),
                new Notified("n-elig-emptypar", "{\"userId\":\"{P}-n-elig-emptypar\",\"userName\":\"P\",\"age\":20,\"balance\":50,\"parentEmail\":\"\"}", false),
                new Notified("n-inel-parent", "{\"userId\":\"{P}-n-inel-parent\",\"userName\":\"P\",\"age\":40,\"balance\":50,\"parentEmail\":\"p2@example.com\"}", false));
    }

    @Nested
    @Order(3)
    @DisplayName("3. Notifications")
    class Notifications {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.mercur.upgrade.e2e.CornerCasesTest#notificationCases")
        void are_sent_to_the_right_people(Notified request) {
            Submitted submitted = Submitted.get();
            JsonNode record = submitted.record(request.user());
            List<JsonNode> mine = submitted.emailsOf(record);
            long user = mine.stream().filter(email -> email.path("role").asText().equals("USER")).count();
            long parent = mine.stream().filter(email -> email.path("role").asText().equals("PARENT")).count();
            MarkdownReport.observe(record.path("status").asText() + "; user emails=" + user + ", parent emails=" + parent);
            assertThat(user).isEqualTo(1);
            assertThat(parent).isEqualTo(request.parentNotified() ? 1 : 0);
        }

        @Test
        @DisplayName("Decline email lists every reason")
        void decline_email_lists_every_reason() {
            Submitted submitted = Submitted.get();
            List<JsonNode> emails = submitted.emailsOf(submitted.record("c-allfail"));
            assertThat(emails).isNotEmpty();
            String body = emails.get(0).path("body").asText();
            MarkdownReport.observe(body);
            assertThat(body).containsPattern(Pattern.compile("User name.*Age.*Balance", Pattern.DOTALL));
        }
    }

    // ---------------------------------------------------------------------------------- 4. Duplicates and batches

    @Nested
    @Order(4)
    @DisplayName("4. Duplicates/batch")
    class DuplicatesAndBatches {

        @Test
        @DisplayName("Same user submitted twice gets distinct eventIds")
        void distinct_event_ids() {
            Submitted submitted = Submitted.get();
            String first = submitted.duplicate1.json().path("eventId").asText();
            String second = submitted.duplicate2.json().path("eventId").asText();
            MarkdownReport.observe(first + " / " + second);
            assertThat(first).isNotBlank().isNotEqualTo(second);
        }

        @Test
        @DisplayName("20 events for same user in one batch accepted")
        void same_user_batch_accepted() {
            ApiClient.Response sequence = Submitted.get().sequence;
            MarkdownReport.observe("HTTP " + sequence.status() + ", accepted=" + sequence.json().path("accepted").asInt());
            assertThat(sequence.status()).isEqualTo(202);
            assertThat(sequence.json().path("accepted").asInt()).isEqualTo(20);
        }

        @Test
        @DisplayName("Mixed batch returns receipts in input order")
        void receipts_in_input_order() {
            ApiClient.Response mixed = Submitted.get().mixed;
            List<String> users = StreamSupport.stream(mixed.json().path("receipts").spliterator(), false)
                    .map(receipt -> receipt.path("userId").asText()).toList();
            MarkdownReport.observe("HTTP " + mixed.status() + ", receipts " + users);
            assertThat(mixed.status()).isEqualTo(202);
            assertThat(users).containsExactly(id("b-ok"), id("b-bad"));
        }

        @Test
        @DisplayName("Both duplicate submissions processed and stored")
        void duplicates_stored() {
            List<JsonNode> records = Submitted.get().records("d-dup");
            MarkdownReport.observe(records.size() + " records");
            assertThat(records).hasSize(2);
        }

        @Test
        @DisplayName("Same-user events processed in submission order")
        void same_user_order() {
            List<String> statuses = Submitted.get().records("o-seq").stream().map(r -> r.path("status").asText()).toList();
            List<String> expected = IntStream.rangeClosed(1, 20).mapToObj(i -> i % 2 == 1 ? "ELIGIBLE" : "INELIGIBLE").toList();
            MarkdownReport.observe(statuses.equals(expected) ? "alternating ELIGIBLE/INELIGIBLE preserved" : statuses.toString());
            assertThat(statuses).isEqualTo(expected);
        }

        @Test
        @DisplayName("Mixed batch outcomes")
        void mixed_batch_outcomes() {
            Submitted submitted = Submitted.get();
            String ok = submitted.record("b-ok").path("status").asText();
            String bad = submitted.record("b-bad").path("status").asText();
            MarkdownReport.observe("b-ok " + ok + ", b-bad " + bad);
            assertThat(ok).isEqualTo("ELIGIBLE");
            assertThat(bad).isEqualTo("INELIGIBLE");
        }

        @Test
        @DisplayName("No request was stored from rejected (400) calls")
        void rejected_calls_store_nothing() {
            long stored = Kubectl.sqlCount("select count(*) from processed_upgrades where user_id in ('"
                    + id("v1") + "', '" + id("v6") + "', '" + id("big1") + "', '" + id("c-unknown") + "')");
            MarkdownReport.observe("v1, v6, big1, c-unknown: " + stored + " records");
            assertThat(stored).isZero();
        }
    }

    // ------------------------------------------------------------------------------------------------------ 5. Load

    @Nested
    @Order(5)
    @DisplayName("5. Load")
    class Load {

        @Test
        @DisplayName("Batch of 5,000 accepted")
        void batch_accepted() {
            LoadRun load = LoadRun.get();
            MarkdownReport.observe("HTTP " + load.batch.status() + ", accepted=" + load.batch.json().path("accepted").asInt()
                    + " in " + load.ingestMillis + " ms");
            assertThat(load.batch.status()).isEqualTo(202);
            assertThat(load.batch.json().path("accepted").asInt()).isEqualTo(5000);
        }

        @Test
        @DisplayName("300 concurrent real-time requests accepted")
        void concurrent_requests_accepted() {
            LoadRun load = LoadRun.get();
            MarkdownReport.observe(load.acceptedAtOnce + " accepted at once, " + load.throttled
                    + " throttled (429) and accepted on retry: " + load.acceptedOnRetry + ", other statuses: " + load.otherStatuses);
            assertThat(load.acceptedAtOnce + load.acceptedOnRetry).isEqualTo(300);
            assertThat(load.otherStatuses).isEmpty();
        }

        @Test
        @DisplayName("All 5,300 load events processed")
        void all_processed() {
            LoadRun load = LoadRun.get();
            MarkdownReport.observe(load.processed + " processed; drained ~" + load.drainMillis + " ms after the batch started");
            assertThat(load.processed).isEqualTo(5300);
        }

        @Test
        @DisplayName("Load batch outcomes (ages 16-25 x balances $25-34)")
        void load_outcomes() {
            Map<String, Long> outcomes = LoadRun.get().outcomes;
            MarkdownReport.observe(outcomes.toString());
            // Item i: age 16 + i % 10, balance 25 + i % 10. Eligible for i % 10 in 5..7 (age 21-23, balance >= $30).
            assertThat(outcomes).containsEntry("ELIGIBLE", 1500L).containsEntry("INELIGIBLE", 3500L);
        }

        @Test
        @DisplayName("Email outbox view stays bounded after load")
        void outbox_bounded() {
            ApiClient.Response emails = API.get("/api/notifications", Session.auth());
            MarkdownReport.observe(emails.json().size() + " retained");
            assertThat(emails.json().size()).isLessThanOrEqualTo(1000);
        }
    }

    // ----------------------------------------------------------------------------------------------------- 6. Query

    @Nested
    @Order(6)
    @DisplayName("6. Query")
    class Query {

        @Test
        @DisplayName("Status filters return only that status")
        void status_filters() {
            List<JsonNode> eligible = list(API.get("/api/processed-upgrades?status=ELIGIBLE&limit=500", Session.auth()));
            List<JsonNode> ineligible = list(API.get("/api/processed-upgrades?status=INELIGIBLE&limit=500", Session.auth()));
            MarkdownReport.observe("ELIGIBLE=" + eligible.size() + ", INELIGIBLE=" + ineligible.size() + " (newest 500 each)");
            assertThat(eligible).isNotEmpty().allSatisfy(r -> assertThat(r.path("status").asText()).isEqualTo("ELIGIBLE"));
            assertThat(ineligible).isNotEmpty().allSatisfy(r -> assertThat(r.path("status").asText()).isEqualTo("INELIGIBLE"));
        }

        @Test
        @DisplayName("status + userId combined filter")
        void combined_filter() {
            int matching = list(API.get("/api/processed-upgrades?status=INELIGIBLE&userId=" + id("c-age17"), Session.auth())).size();
            int other = list(API.get("/api/processed-upgrades?status=ELIGIBLE&userId=" + id("c-age17"), Session.auth())).size();
            MarkdownReport.observe("INELIGIBLE: " + matching + " record, ELIGIBLE: " + other);
            assertThat(matching).isEqualTo(1);
            assertThat(other).isZero();
        }

        @Test
        @DisplayName("Unknown userId returns empty list")
        void unknown_user() {
            ApiClient.Response response = API.get("/api/processed-upgrades?userId=" + id("nobody"), Session.auth());
            MarkdownReport.observe(response.summary());
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().strip()).isEqualTo("[]");
        }

        @Test
        @DisplayName("Per-user results ordered by processedAt")
        void ordered_by_processed_at() {
            Submitted submitted = Submitted.get();
            for (String user : List.of("o-seq", "d-dup")) {
                List<OffsetDateTime> times = submitted.records(user).stream()
                        .map(r -> OffsetDateTime.parse(r.path("processedAt").asText())).toList();
                assertThat(times).as(user).isSorted();
            }
        }

        @Test
        @DisplayName("Health endpoint")
        void health() {
            ApiClient.Response health = API.get("/actuator/health");
            MarkdownReport.observe(health.summary());
            assertThat(health.status()).isEqualTo(200);
            assertThat(health.body()).contains("UP");
        }
    }

    // ------------------------------------------------------------------------------------------------ shared state

    /** The asynchronous requests of groups 2-4, submitted once and waited for; then their records and emails. */
    static final class Submitted {

        private static Submitted instance;
        private static RuntimeException failure;

        final Map<String, ApiClient.Response> ingestion = new LinkedHashMap<>();
        final ApiClient.Response duplicate1;
        final ApiClient.Response duplicate2;
        final ApiClient.Response sequence;
        final ApiClient.Response mixed;
        private final Map<String, List<JsonNode>> records = new HashMap<>();
        private final List<JsonNode> emails;

        private Submitted() {
            for (Eligibility c : eligibilityCases()) {
                ingestion.put(c.user(), API.post("/api/realtime-upgrade", json(c.json()), Session.auth()));
            }
            for (Notified c : notificationCases()) {
                ingestion.put(c.user(), API.post("/api/realtime-upgrade", json(c.json()), Session.auth()));
            }
            String duplicate = json("{\"userId\":\"{P}-d-dup\",\"userName\":\"D\",\"age\":20,\"balance\":50}");
            duplicate1 = API.post("/api/realtime-upgrade", duplicate, Session.auth());
            duplicate2 = API.post("/api/realtime-upgrade", duplicate, Session.auth());
            sequence = API.post("/api/batch-upgrade", IntStream.rangeClosed(1, 20)
                    .mapToObj(i -> json("{\"userId\":\"{P}-o-seq\",\"userName\":\"S\",\"age\":" + (i % 2 == 1 ? 20 : 30) + ",\"balance\":50}"))
                    .collect(Collectors.joining(",", "[", "]")), Session.auth());
            mixed = API.post("/api/batch-upgrade", json("[{\"userId\":\"{P}-b-ok\",\"userName\":\"B\",\"age\":19,\"balance\":100,"
                    + "\"parentEmail\":\"bp@example.com\"},{\"userId\":\"{P}-b-bad\",\"userName\":\"\",\"age\":16,\"balance\":5}]"),
                    Session.auth());

            long expected = eligibilityCases().size() + notificationCases().size() + 2 + 20 + 2;
            String mine = "user_id like '" + P + "-%' and user_id not like '" + P + "-load%' and user_id not like '" + P + "-par%'";
            await("the " + expected + " corner-case requests processed and notified")
                    .atMost(E2eSettings.PROCESSING_TIMEOUT).pollInterval(Duration.ofSeconds(1))
                    .until(() -> Kubectl.sqlCount("select count(*) from processed_upgrades where " + mine) == expected
                            && Kubectl.sqlCount("select count(*) from notification_outbox o join processed_upgrades p "
                            + "on p.event_id = o.event_id where p." + mine + " and o.sent_at is null") == 0);

            List<String> users = new ArrayList<>(ingestion.keySet());
            users.addAll(List.of("d-dup", "o-seq", "b-ok", "b-bad"));
            for (String user : users) {
                records.put(user, list(API.get("/api/processed-upgrades?userId=" + id(user), Session.auth())));
            }
            // The outbox view holds the newest 1,000 emails: read it before the load group adds thousands.
            emails = list(API.get("/api/notifications", Session.auth()));
        }

        static synchronized Submitted get() {
            if (failure != null) {
                throw failure;
            }
            if (instance == null) {
                try {
                    instance = new Submitted();
                }
                catch (RuntimeException | AssertionError e) {
                    failure = e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
                    throw failure;
                }
            }
            return instance;
        }

        List<JsonNode> records(String user) {
            return records.getOrDefault(user, List.of());
        }

        JsonNode record(String user) {
            List<JsonNode> rows = records(user);
            assertThat(rows).as("records of %s", user).isNotEmpty();
            return rows.get(0);
        }

        List<JsonNode> emailsOf(JsonNode record) {
            String eventId = record.path("eventId").asText();
            return emails.stream().filter(email -> email.path("eventId").asText().equals(eventId)).toList();
        }
    }

    /** The load of group 5: a batch of 5,000 and 300 concurrent real-time requests, then waited for. */
    static final class LoadRun {

        private static final int MAX_IN_FLIGHT = 100;
        private static LoadRun instance;
        private static RuntimeException failure;

        final ApiClient.Response batch;
        final long ingestMillis;
        final int acceptedAtOnce;
        final int throttled;
        final int acceptedOnRetry;
        final List<Integer> otherStatuses = new ArrayList<>();
        final long processed;
        final long drainMillis;
        final Map<String, Long> outcomes = new LinkedHashMap<>();

        private LoadRun() {
            String items = IntStream.rangeClosed(1, 5000)
                    .mapToObj(i -> "{\"userId\":\"" + id("load" + i) + "\",\"userName\":\"L\",\"age\":" + (16 + i % 10)
                            + ",\"balance\":" + (25 + i % 10) + "}")
                    .collect(Collectors.joining(",", "[", "]"));
            long start = System.nanoTime();
            batch = API.post("/api/batch-upgrade", items, Session.auth());
            ingestMillis = (System.nanoTime() - start) / 1_000_000;

            // Concurrently and not retried, so the rate limit shows; then the throttled ones again, as a client would.
            // At most 100 in flight: a burst of 300 new connections is refused by the host's port forwarding to the
            // cluster (Rancher Desktop), before it reaches the application.
            List<String> bodies = IntStream.rangeClosed(1, 300)
                    .mapToObj(i -> "{\"userId\":\"" + id("par" + i) + "\",\"userName\":\"P\",\"age\":20,\"balance\":50}")
                    .toList();
            Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (String body : bodies) {
                inFlight.acquireUninterruptibly();
                calls.add(API.postAsync("/api/realtime-upgrade", body, Session.auth())
                        .whenComplete((status, error) -> inFlight.release()));
            }
            List<String> retry = new ArrayList<>();
            int accepted = 0;
            for (int i = 0; i < calls.size(); i++) {
                int status = calls.get(i).join();
                if (status == 202) {
                    accepted++;
                }
                else if (status == 429) {
                    retry.add(bodies.get(i));
                }
                else {
                    otherStatuses.add(status);
                }
            }
            acceptedAtOnce = accepted;
            throttled = retry.size();
            acceptedOnRetry = (int) retry.stream()
                    .filter(body -> API.post("/api/realtime-upgrade", body, Session.auth()).status() == 202).count();

            String load = "(user_id like '" + P + "-load%' or user_id like '" + P + "-par%')";
            await("the 5,300 load events processed").atMost(LOAD_TIMEOUT).pollInterval(Duration.ofSeconds(1))
                    .until(() -> Kubectl.sqlCount("select count(*) from processed_upgrades where " + load) >= 5300);
            drainMillis = (System.nanoTime() - start) / 1_000_000;
            processed = Kubectl.sqlCount("select count(*) from processed_upgrades where " + load);
            Kubectl.sql("select status || '=' || count(*) from processed_upgrades where user_id like '" + P
                    + "-load%' group by status order by status").lines()
                    .map(line -> line.split("="))
                    .forEach(pair -> outcomes.put(pair[0], Long.parseLong(pair[1])));
        }

        static synchronized LoadRun get() {
            if (failure != null) {
                throw failure;
            }
            if (instance == null) {
                try {
                    instance = new LoadRun();
                }
                catch (RuntimeException | AssertionError e) {
                    failure = e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
                    throw failure;
                }
            }
            return instance;
        }
    }

    static List<JsonNode> list(ApiClient.Response response) {
        List<JsonNode> rows = new ArrayList<>();
        if (response.status() == 200 && response.json() != null) {
            response.json().forEach(rows::add);
        }
        return rows;
    }

    static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }
}
