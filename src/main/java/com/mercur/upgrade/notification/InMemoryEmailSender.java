package com.mercur.upgrade.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock email channel: logs each email and keeps the most recent ones in memory for inspection.
 *
 * <p>The outbox is a bounded ring buffer: an unbounded list would grow with every processed request
 * (a memory leak), and a copy-on-write list would copy the whole history on every send while holding
 * a lock shared by all consumer threads.
 */
@Component
public class InMemoryEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEmailSender.class);

    private final int capacity;
    private final Deque<EmailMessage> recent = new ArrayDeque<>();
    private final AtomicLong totalSent = new AtomicLong();

    public InMemoryEmailSender(@Value("${upgrade.notification.outbox-capacity:1000}") int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("upgrade.notification.outbox-capacity must be at least 1");
        }
        this.capacity = capacity;
    }

    @Override
    public void send(EmailMessage message) {
        log.info("[EMAIL] to {} {} | {} | {}", message.role(), message.recipient(), message.subject(), message.body());
        synchronized (recent) {
            if (recent.size() == capacity) {
                recent.removeFirst();
            }
            recent.addLast(message);
        }
        totalSent.incrementAndGet();
    }

    /** The most recent emails, oldest first, at most {@code upgrade.notification.outbox-capacity}. */
    public List<EmailMessage> sentMessages() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    /** Total number of emails sent since start. */
    public long totalSent() {
        return totalSent.get();
    }
}
