package com.mercur.upgrade.messaging.inmemory;

import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.EventPublisher;
import com.mercur.upgrade.messaging.MessagingProperties;
import com.mercur.upgrade.messaging.PublishException;
import com.mercur.upgrade.messaging.UpgradeRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory stand-in for the Kafka {@code upgrade-requests} topic (active unless the "kafka" profile is on).
 *
 * <p>Mirrors the Kafka semantics that matter to this service:
 * <ul>
 *   <li>events are partitioned by key (userId), so events of one user are processed in order;</li>
 *   <li>each partition has exactly one consumer thread, so partitions are processed in parallel;</li>
 *   <li>bounded partitions provide back-pressure: publishing to a full partition fails fast;</li>
 *   <li>failed deliveries are retried and finally parked in a bounded dead-letter store.</li>
 * </ul>
 *
 * <p>Thread-safety: {@code publish} runs under the read lock and {@code start}/{@code stop} flip
 * {@code running} under the write lock. Once {@code stop} holds the write lock no publish can be
 * half-way through, so every event that was accepted is already queued and gets drained.
 */
@Component
@Profile("!kafka")
public class InMemoryMessageBroker implements EventPublisher, SmartLifecycle {

    /** Start before and stop after the embedded web server, so HTTP requests never meet a stopped broker. */
    public static final int PHASE =SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageBroker.class);
    private static final long POLL_TIMEOUT_MS = 100;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 20;

    private final UpgradeRequestHandler handler;
    private final MessagingProperties properties;
    private final List<BlockingQueue<UpgradeRequestedEvent>> partitions;
    private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Deque<DeadLetter> deadLetters = new ArrayDeque<>();
    private final AtomicLong deadLetterCount = new AtomicLong();

    private volatile boolean running;
    private ExecutorService consumers;

    public InMemoryMessageBroker(UpgradeRequestHandler handler, MessagingProperties properties) {
        this.handler = handler;
        this.properties = properties;
        this.partitions = new ArrayList<>(properties.partitions());
        for (int i = 0; i < properties.partitions(); i++) {
            partitions.add(new ArrayBlockingQueue<>(properties.queueCapacity()));
        }
    }

    @Override
    public void publish(UpgradeRequestedEvent event) {
        lifecycleLock.readLock().lock();
        try {
            if (!running) {
                throw new PublishException("Message broker is not running");
            }
            int partition = partitionFor(event.key());
            if (!partitions.get(partition).offer(event)) {
                throw new PublishException("Partition " + partition + " of topic upgrade-requests is full");
            }
            log.debug("Published event {} for user {} to partition {}", event.eventId(), event.key(), partition);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    int partitionFor(String key) {
        return Math.floorMod(key.hashCode(), partitions.size());
    }

    @Override
    public void start() {
        lifecycleLock.writeLock().lock();
        try {
            if (running) {
                return; // a second consumer per partition would break per-user ordering
            }
            AtomicInteger threadIndex = new AtomicInteger();
            consumers = Executors.newFixedThreadPool(partitions.size(), runnable -> {
                Thread thread = new Thread(runnable, "upgrade-consumer-" + threadIndex.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            });
            running = true;
            for (BlockingQueue<UpgradeRequestedEvent> partition : partitions) {
                consumers.execute(() -> consume(partition));
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
        log.info("In-memory broker started with {} partitions", partitions.size());
    }

    private void consume(BlockingQueue<UpgradeRequestedEvent> partition) {
        // Keep draining after stop() so accepted events are not lost on graceful shutdown.
        while (running || !partition.isEmpty()) {
            UpgradeRequestedEvent event;
            try {
                event = partition.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (event == null) {
                continue;
            }
            inFlight.incrementAndGet();
            try {
                deliver(event);
            } catch (InterruptedException e) {
                deadLetter(event, "Interrupted during forced shutdown");
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // Never let one event kill the partition's only consumer (that would stall it forever).
                log.error("Unexpected error while processing event {}", event.eventId(), t);
                deadLetter(event, t.toString());
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    private void deliver(UpgradeRequestedEvent event) throws InterruptedException {
        Exception lastError = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                handler.handle(event);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("Attempt {}/{} failed for event {}: {}",
                        attempt, properties.maxAttempts(), event.eventId(), e.getMessage());
                if (attempt < properties.maxAttempts()) {
                    Thread.sleep(properties.retryBackoff().toMillis());
                }
            }
        }
        log.error("Event {} moved to dead-letter store after {} attempts", event.eventId(), properties.maxAttempts(), lastError);
        deadLetter(event, lastError.toString());
    }

    private void deadLetter(UpgradeRequestedEvent event, String error) {
        deadLetterCount.incrementAndGet();
        synchronized (deadLetters) {
            if (deadLetters.size() == properties.deadLetterCapacity()) {
                deadLetters.removeFirst();
            }
            deadLetters.addLast(new DeadLetter(event, error, Instant.now()));
        }
    }

    @Override
    public void stop() {
        ExecutorService toStop;
        lifecycleLock.writeLock().lock();
        try {
            if (!running) {
                return;
            }
            running = false; // from here on publish() rejects; everything accepted is already queued
            toStop = consumers;
            consumers = null;
        } finally {
            lifecycleLock.writeLock().unlock();
        }

        toStop.shutdown();
        try {
            if (!toStop.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Consumers did not drain within {}s, forcing shutdown", SHUTDOWN_TIMEOUT_SECONDS);
                toStop.shutdownNow();
                toStop.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            toStop.shutdownNow();
            Thread.currentThread().interrupt();
        }
        int undelivered = partitions.stream().mapToInt(BlockingQueue::size).sum();
        if (undelivered > 0) {
            log.error("In-memory broker stopped with {} undelivered events", undelivered);
        }
        log.info("In-memory broker stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** Events waiting in partitions plus events currently being processed. */
    public int pendingCount() {
        return partitions.stream().mapToInt(BlockingQueue::size).sum() + inFlight.get();
    }

    /** Total number of events dead-lettered since start (the store itself only keeps the most recent). */
    public long deadLetterCount() {
        return deadLetterCount.get();
    }

    /** The most recent dead letters, oldest first, at most {@code dead-letter-capacity}. */
    public List<DeadLetter> deadLetters() {
        synchronized (deadLetters) {
            return List.copyOf(deadLetters);
        }
    }

    /** An event that could not be processed. */
    public record DeadLetter(UpgradeRequestedEvent event, String error, Instant failedAt) {
    }
}
