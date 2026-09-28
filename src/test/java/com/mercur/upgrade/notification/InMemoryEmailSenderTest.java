package com.mercur.upgrade.notification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mercur.upgrade.TestRequests.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEmailSenderTest {

    @Test
    void outboxKeepsOnlyTheMostRecentMessagesButCountsAll() {
        InMemoryEmailSender sender = new InMemoryEmailSender(100);

        for (int i = 0; i < 10_000; i++) {
            sender.send(message("evt-" + i));
        }

        assertThat(sender.totalSent()).isEqualTo(10_000);
        assertThat(sender.sentMessages()).hasSize(100);
        assertThat(sender.sentMessages().get(0).eventId()).isEqualTo("evt-9900");
        assertThat(sender.sentMessages().get(99).eventId()).isEqualTo("evt-9999");
    }

    @Test
    void concurrentSendersNeverExceedCapacityOrLoseCount() throws Exception {
        InMemoryEmailSender sender = new InMemoryEmailSender(50);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Object>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 5_000; i++) {
                    sender.send(message("x"));
                    assertThat(sender.sentMessages().size()).isLessThanOrEqualTo(50);
                }
                return null;
            }));
        }
        for (Future<Object> future : futures) {
            future.get(30, TimeUnit.SECONDS); // rethrows assertion failures from worker threads
        }
        pool.shutdown();

        assertThat(sender.totalSent()).isEqualTo(40_000);
        assertThat(sender.sentMessages()).hasSize(50);
    }

    private static EmailMessage message(String eventId) {
        return new EmailMessage(eventId, RecipientRole.USER, "u", "s", "b", NOW);
    }
}
