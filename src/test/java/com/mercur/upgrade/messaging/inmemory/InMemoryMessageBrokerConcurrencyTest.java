package com.mercur.upgrade.messaging.inmemory;

import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.MessagingProperties;
import com.mercur.upgrade.messaging.PublishException;
import com.mercur.upgrade.messaging.UpgradeRequestHandler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.mercur.upgrade.TestRequests.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Multi-threading, lifecycle and resource-bound behaviour of the in-memory broker. */
class InMemoryMessageBrokerConcurrencyTest {

    @Test
    void everyAcceptedEventIsDeliveredExactlyOnceUnderConcurrentPublishers() throws Exception {
        Set<String> delivered = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        InMemoryMessageBroker broker = start(e -> {
            if (!delivered.add(e.eventId())) {
                duplicates.incrementAndGet();
            }
        }, properties(8, 100_000, 1, 10));

        int publishers = 16;
        int perPublisher = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(publishers);
        CountDownLatch go = new CountDownLatch(1);
        for (int p = 0; p < publishers; p++) {
            int publisher = p;
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < perPublisher; i++) {
                    broker.publish(event("p" + publisher + "-" + i, "user-" + (i % 97)));
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        await().atMost(Duration.ofSeconds(30)).until(() -> delivered.size() == publishers * perPublisher);
        assertThat(duplicates).hasValue(0);
        broker.stop();
    }

    @Test
    void preservesPerKeyOrderAndNeverProcessesOneKeyConcurrently() {
        Set<String> keysInProgress = ConcurrentHashMap.newKeySet();
        AtomicInteger overlaps = new AtomicInteger();
        List<String> orderForHotKey = new CopyOnWriteArrayList<>();
        InMemoryMessageBroker broker = start(e -> {
            if (!keysInProgress.add(e.key())) {
                overlaps.incrementAndGet();
            }
            if (e.key().equals("hot")) {
                orderForHotKey.add(e.eventId());
            }
            keysInProgress.remove(e.key());
        }, properties(4, 50_000, 1, 10));

        List<String> expected = IntStream.range(0, 2_000).mapToObj(i -> "hot-" + i).toList();
        for (int i = 0; i < 2_000; i++) {
            broker.publish(event("hot-" + i, "hot"));
            broker.publish(event("cold-" + i, "cold-" + i));
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> orderForHotKey.size() == 2_000 && broker.pendingCount() == 0);
        assertThat(orderForHotKey).containsExactlyElementsOf(expected);
        assertThat(overlaps).hasValue(0);
        broker.stop();
    }

    @Test
    void publishRacingWithStopNeverLosesAnAcceptedEvent() throws Exception {
        for (int round = 0; round < 200; round++) {
            Set<String> delivered = ConcurrentHashMap.newKeySet();
            Set<String> accepted = ConcurrentHashMap.newKeySet();
            InMemoryMessageBroker broker = start(e -> delivered.add(e.eventId()), properties(2, 100_000, 1, 1));

            int roundNo = round;
            Thread publisher = new Thread(() -> {
                for (int i = 0; ; i++) {
                    String id = "r" + roundNo + "-" + i;
                    try {
                        broker.publish(event(id, "user-" + i));
                        accepted.add(id);
                    } catch (PublishException rejected) {
                        return;
                    }
                }
            });
            publisher.start();
            Thread.sleep(1);
            broker.stop();
            publisher.join();

            assertThat(delivered).as("round %d: accepted events must not be lost on stop", round)
                    .containsAll(accepted);
        }
    }

    @Test
    void handlerErrorDoesNotKillThePartitionConsumer() {
        List<String> delivered = new CopyOnWriteArrayList<>();
        InMemoryMessageBroker broker = start(e -> {
            if (e.eventId().equals("poison")) {
                throw new AssertionError("unexpected Error from handler");
            }
            delivered.add(e.eventId());
        }, properties(1, 100, 1, 1));

        broker.publish(event("poison", "u"));
        broker.publish(event("after-poison", "u"));

        await().atMost(Duration.ofSeconds(5)).until(() -> delivered.contains("after-poison"));
        assertThat(broker.deadLetterCount()).isEqualTo(1);
        broker.stop();
    }

    @Test
    void startingTwiceDoesNotCreateDuplicateConsumers() {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        InMemoryMessageBroker broker = start(e -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            try {
                Thread.sleep(1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            handled.incrementAndGet();
        }, properties(1, 1_000, 1, 1));
        broker.start();

        for (int i = 0; i < 200; i++) {
            broker.publish(event("e" + i, "same-user"));
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> handled.get() == 200);
        assertThat(maxConcurrent).as("one partition must have exactly one consumer").hasValue(1);
        broker.stop();
        assertThat(consumerThreadsAlive()).isZero();
    }

    @Test
    void stopTerminatesAllConsumerThreads() {
        InMemoryMessageBroker broker = start(e -> { }, properties(6, 10, 1, 1));
        await().atMost(Duration.ofSeconds(5)).until(() -> consumerThreadsAlive() >= 6);

        broker.stop();

        await().atMost(Duration.ofSeconds(5)).until(() -> consumerThreadsAlive() == 0);
    }

    @Test
    void deadLetterStoreIsBounded() {
        InMemoryMessageBroker broker = start(e -> {
            throw new IllegalStateException("always fails");
        }, new MessagingProperties(1, 10_000, 1, Duration.ZERO, 50));

        for (int i = 0; i < 500; i++) {
            broker.publish(event("dl-" + i, "u"));
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> broker.deadLetterCount() == 500);
        assertThat(broker.deadLetters()).hasSize(50);
        assertThat(broker.deadLetters().get(49).event().eventId()).isEqualTo("dl-499");
        broker.stop();
    }

    private static long consumerThreadsAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("upgrade-consumer-") && t.isAlive())
                .count();
    }

    private static InMemoryMessageBroker start(UpgradeRequestHandler handler, MessagingProperties properties) {
        InMemoryMessageBroker broker = new InMemoryMessageBroker(handler, properties);
        broker.start();
        return broker;
    }

    private static MessagingProperties properties(int partitions, int capacity, int maxAttempts, int backoffMs) {
        return new MessagingProperties(partitions, capacity, maxAttempts, Duration.ofMillis(backoffMs), 1_000);
    }

    private static UpgradeRequestedEvent event(String eventId, String userId) {
        return new UpgradeRequestedEvent(eventId, RequestSource.BATCH, NOW,
                new UpgradeRequest(userId, "Name", 20, BigDecimal.TEN, null));
    }
}
