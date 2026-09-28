package com.mercur.upgrade.messaging.inmemory;

import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.MessagingProperties;
import com.mercur.upgrade.messaging.PublishException;
import com.mercur.upgrade.messaging.UpgradeRequestHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.mercur.upgrade.TestRequests.NOW;
import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class InMemoryMessageBrokerTest {

    private InMemoryMessageBroker broker;

    @AfterEach
    void tearDown() {
        if (broker != null && broker.isRunning()) {
            broker.stop();
        }
    }

    @Test
    void deliversEventsOfSameKeyInPublishOrder() {
        List<String> received = new CopyOnWriteArrayList<>();
        broker = start(event -> received.add(event.eventId()), 4, 100, 1);

        List<String> published = IntStream.range(0, 50)
                .mapToObj(i -> new UpgradeRequestedEvent("evt-" + i, RequestSource.BATCH, NOW, eligible()))
                .peek(broker::publish)
                .map(UpgradeRequestedEvent::eventId)
                .toList();

        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 50);
        assertThat(received).containsExactlyElementsOf(published);
    }

    @Test
    void retriesFailedDeliveryThenDeadLettersIt() {
        AtomicInteger attempts = new AtomicInteger();
        broker = start(event -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }, 1, 10, 3);

        broker.publish(event(eligible()));

        await().atMost(Duration.ofSeconds(5)).until(() -> broker.deadLetters().size() == 1);
        assertThat(attempts).hasValue(3);
        assertThat(broker.deadLetters().get(0).error()).contains("boom");
    }

    @Test
    void recoversWhenRetrySucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        broker = start(event -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
            }
            delivered.countDown();
        }, 1, 10, 3);

        broker.publish(event(eligible()));

        await().atMost(Duration.ofSeconds(5)).until(() -> delivered.getCount() == 0);
        assertThat(broker.deadLetters()).isEmpty();
    }

    @Test
    void rejectsPublishWhenPartitionIsFull() {
        CountDownLatch consuming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        broker = start(event -> {
            consuming.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 1, 1, 1);

        broker.publish(event(eligible()));                     // taken by the consumer, which then blocks
        await().atMost(Duration.ofSeconds(5)).until(() -> consuming.getCount() == 0);
        broker.publish(event(user("u-2")));                    // fills the single slot

        assertThatThrownBy(() -> broker.publish(event(user("u-3"))))
                .isInstanceOf(PublishException.class)
                .hasMessageContaining("full");
        release.countDown();
    }

    @Test
    void rejectsPublishWhenNotRunning() {
        broker = new InMemoryMessageBroker(event -> { }, properties(1, 10, 1));

        assertThatThrownBy(() -> broker.publish(event(eligible()))).isInstanceOf(PublishException.class);
    }

    private static InMemoryMessageBroker start(UpgradeRequestHandler handler, int partitions, int capacity,
                                               int maxAttempts) {
        InMemoryMessageBroker broker = new InMemoryMessageBroker(handler, properties(partitions, capacity, maxAttempts));
        broker.start();
        return broker;
    }

    private static MessagingProperties properties(int partitions, int capacity, int maxAttempts) {
        return new MessagingProperties(partitions, capacity, maxAttempts, Duration.ofMillis(10), 100);
    }

    private static UpgradeRequest user(String userId) {
        return new UpgradeRequest(userId, "Name", 20, BigDecimal.TEN, null);
    }
}
