package com.mercur.upgrade.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Soak test: sends batches of upgrade requests to the backend as fast as it accepts them, waits until every accepted
 * request is processed, and appends one row per run to test-results/soak-history.md. Fails if an accepted request is
 * lost or dead-lettered.
 *
 * <p>Not part of the default run (nor the pipeline's): {@code mvnw verify -Psoak}. Needs kubectl access to the
 * deployment: counts come from PostgreSQL and Kafka, thread and heap figures from each backend pod's actuator.
 *
 * <pre>
 *   -De2e.soak.batches=10       batches (sequential)
 *   -De2e.soak.batchSize=10000  requests per batch (the API's maximum)
 *   -De2e.soak.timeout=900      seconds to wait for processing
 *   -De2e.soak.note="..."       free text for the history row
 * </pre>
 * The defaults add 100,000 requests to the database.
 */
@Tag("soak")
@DisplayName("Soak")
class SoakTest {

    static final Path HISTORY = Path.of("test-results", "soak-history.md");

    private static final int BATCHES = Integer.parseInt(E2eSettings.setting("e2e.soak.batches", "E2E_SOAK_BATCHES", "10"));
    private static final int BATCH_SIZE =
            Integer.parseInt(E2eSettings.setting("e2e.soak.batchSize", "E2E_SOAK_BATCH_SIZE", "10000"));
    private static final Duration TIMEOUT =
            Duration.ofSeconds(Long.parseLong(E2eSettings.setting("e2e.soak.timeout", "E2E_SOAK_TIMEOUT", "900")));
    private static final String NOTE = E2eSettings.setting("e2e.soak.note", "E2E_SOAK_NOTE", "");

    private final ApiClient api = new ApiClient(E2eSettings.API_URL, true);

    @Test
    void every_accepted_request_is_processed() throws IOException {
        String prefix = "soak-" + System.currentTimeMillis();
        String mine = "user_id like '" + prefix + "-%'";
        List<String> pods = Kubectl.pods("backend");
        String threadsBefore = perPod(pods, pod -> Kubectl.backendMetric(pod, "jvm.threads.live"));
        long deadLettersBefore = Kubectl.deadLetters();

        int accepted = 0;
        int rejected = 0;
        long start = System.nanoTime();
        for (int b = 0; b < BATCHES; b++) {
            int batch = b;
            String items = IntStream.rangeClosed(1, BATCH_SIZE)
                    .mapToObj(i -> "{\"userId\":\"" + prefix + "-" + batch + "-" + i
                            + "\",\"userName\":\"S\",\"age\":20,\"balance\":50,\"parentEmail\":\"p@example.com\"}")
                    .collect(Collectors.joining(",", "[", "]"));
            ApiClient.Response response = api.post("/api/batch-upgrade", items, Session.auth());
            assertThat(response.status()).as("batch %d: %s", b, response.summary()).isEqualTo(202);
            accepted += response.json().path("accepted").asInt();
            rejected += response.json().path("rejected").asInt();
            System.out.printf("batch %d -> HTTP %d accepted=%d rejected=%d at %d ms%n", b, response.status(),
                    response.json().path("accepted").asInt(), response.json().path("rejected").asInt(), millisSince(start));
        }

        int expected = accepted;
        await("all " + expected + " accepted requests processed").atMost(TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> Kubectl.sqlCount("select count(*) from processed_upgrades where " + mine) >= expected);
        double seconds = millisSince(start) / 1000.0;
        long processed = Kubectl.sqlCount("select count(*) from processed_upgrades where " + mine);
        long notified = Kubectl.sqlCount("select count(*) from processed_upgrades p where p." + mine
                + " and not exists (select 1 from notification_outbox o where o.event_id = p.event_id and o.sent_at is null)");
        long deadLetters = Kubectl.deadLetters() - deadLettersBefore;
        String threadsAfter = perPod(pods, pod -> Kubectl.backendMetric(pod, "jvm.threads.live"));
        String heapMb = perPod(pods, pod -> Kubectl.backendMetric(pod, "jvm.memory.used?tag=area:heap") / (1024 * 1024));
        long rate = Math.round(processed / seconds);

        System.out.printf("accepted=%d rejected=%d processed=%d in %.1f s (~%d events/s end-to-end)%n",
                accepted, rejected, processed, seconds, rate);
        System.out.printf("notified so far=%d dead letters=%d; JVM threads per pod %s -> %s; heap used per pod %s MB%n",
                notified, deadLetters, threadsBefore, threadsAfter, heapMb);
        appendHistory(String.join(" | ", "", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                String.valueOf(BATCHES * BATCH_SIZE), String.valueOf(accepted), String.valueOf(rejected),
                String.valueOf(processed), String.valueOf(accepted - processed), String.valueOf(notified),
                String.valueOf(deadLetters), "%.1f".formatted(seconds), String.valueOf(rate),
                threadsBefore + " -> " + threadsAfter, heapMb, NOTE.replace("|", "\\|"), "").strip());

        assertThat(processed).as("lost: accepted but not processed").isEqualTo(accepted);
        assertThat(deadLetters).as("dead letters").isZero();
    }

    private static String perPod(List<String> pods, ToDoubleFunction<String> metric) {
        return pods.stream().map(pod -> String.valueOf(Math.round(metric.applyAsDouble(pod))))
                .collect(Collectors.joining("/"));
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void appendHistory(String row) throws IOException {
        Files.createDirectories(HISTORY.getParent());
        if (!Files.exists(HISTORY)) {
            Files.writeString(HISTORY, """
                    # Soak test history

                    Rows are appended by `SoakTest` (`account-upgrade-e2e`, `mvnw verify -Psoak`), one per run, against the \
                    Kubernetes deployment (2 backend pods, 3 Kafka brokers, PostgreSQL).

                    - **Rejected:** batch items refused at ingestion (e.g. Kafka unavailable), not lost data.
                    - **Lost:** accepted minus processed; this must always be 0.
                    - **Notified:** processed requests whose emails were all delivered when processing finished; the outbox \
                    keeps delivering after that.
                    - **Threads / heap:** per backend pod (`pod1/pod2`), from its actuator (`jvm.threads.live`, \
                    `jvm.memory.used` for the heap).

                    | Run at | Events sent | Accepted | Rejected | Processed | Lost | Notified | Dead letters | Seconds | Events/s | JVM threads before -> after | Heap used (MB) | Note |
                    |---|---|---|---|---|---|---|---|---|---|---|---|---|
                    """);
        }
        Files.writeString(HISTORY, row + "\n", StandardOpenOption.APPEND);
        System.out.println("Row appended to " + HISTORY.toAbsolutePath());
    }
}
