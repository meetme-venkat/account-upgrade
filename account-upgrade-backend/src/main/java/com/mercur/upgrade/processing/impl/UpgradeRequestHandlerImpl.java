package com.mercur.upgrade.processing.impl;

import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.eligibility.EligibilityResult;
import com.mercur.upgrade.eligibility.EligibilityService;
import com.mercur.upgrade.messaging.UpgradeRequestHandler;
import com.mercur.upgrade.notification.NotificationService;
import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.persistence.ProcessingStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer of the {@code upgrade-requests} topic: eligibility check -> notification -> persistence.
 *
 * <p>Delivery is at-least-once, so events that were already processed are skipped by event id.
 *
 * <p>The check, the notifications (outbox rows) and the decision are written in <b>one transaction</b>:
 * the emails exist if and only if the decision is committed. A crash or a database error rolls everything
 * back and the redelivered event is processed cleanly; a concurrent duplicate (two instances, after a
 * rebalance) finds the decision taken when storing it, and writes no emails.
 *
 * <p>{@link #handleAll} does the same for a batch of events in one transaction, with a few statements for the
 * whole batch instead of a few per event: the consumers' main path.
 */
@Service
public class UpgradeRequestHandlerImpl implements UpgradeRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(UpgradeRequestHandlerImpl.class);

    private final EligibilityService eligibilityService;
    private final NotificationService notificationService;
    private final ProcessedUpgradeRepository repository;
    private final Clock clock;
    private final TransactionOperations transactions;
    /** Event ids currently being processed; bounded by the number of consumer threads. */
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();
    /** Decisions committed by this instance, per status: {@code upgrade.decisions}. */
    private final Map<ProcessingStatus, Counter> decisions = new EnumMap<>(ProcessingStatus.class);

    public UpgradeRequestHandlerImpl(EligibilityService eligibilityService,
                                   NotificationService notificationService,
                                   ProcessedUpgradeRepository repository,
                                   Clock clock,
                                   TransactionOperations transactions,
                                   MeterRegistry meterRegistry) {
        this.eligibilityService = eligibilityService;
        this.notificationService = notificationService;
        this.repository = repository;
        this.clock = clock;
        this.transactions = transactions;
        for (ProcessingStatus status : ProcessingStatus.values()) {
            decisions.put(status, Counter.builder("upgrade.decisions").tag("status", status.name())
                    .description("Decisions stored by this instance (counted once their transaction commits)")
                    .register(meterRegistry));
        }
    }

    @Override
    public void handle(UpgradeRequestedEvent event) {
        // Claim the event first: without it, two concurrent deliveries of the same event on this instance would
        // both pass the "already processed?" check. The claim is held until the transaction has committed or
        // rolled back; across instances, the database's unique keys take over.
        if (!inProgress.add(event.eventId())) {
            log.info("Skipping event {}: already being processed by another consumer", event.eventId());
            return;
        }
        try {
            count(transactions.execute(status -> processOnce(event)));
        } finally {
            inProgress.remove(event.eventId()); // released on failure too, so a retry can process it
        }
    }

    @Override
    public void handleAll(List<UpgradeRequestedEvent> events) {
        // The same claims as handle(), for every event of the batch; an event delivered twice within the batch is
        // processed once.
        List<UpgradeRequestedEvent> claimed = new ArrayList<>(events.size());
        Set<String> inBatch = new HashSet<>();
        for (UpgradeRequestedEvent event : events) {
            if (!inBatch.add(event.eventId())) {
                log.info("Skipping duplicate event {} within the batch", event.eventId());
            } else if (!inProgress.add(event.eventId())) {
                log.info("Skipping event {}: already being processed by another consumer", event.eventId());
            } else {
                claimed.add(event);
            }
        }
        try {
            if (!claimed.isEmpty()) {
                count(transactions.execute(status -> processBatch(claimed)));
            }
        } finally {
            claimed.forEach(event -> inProgress.remove(event.eventId()));
        }
    }

    /**
     * Three statements for the whole batch: which events are already stored, the new decisions (one insert that
     * returns what it stored), and the emails of the decisions stored here. A decision another instance stored
     * first is left out, and so are its emails. Returns the decisions stored here.
     */
    private List<ProcessedUpgrade> processBatch(List<UpgradeRequestedEvent> events) {
        Set<String> alreadyStored =
                repository.findStoredEventIds(events.stream().map(UpgradeRequestedEvent::eventId).toList());
        Map<UpgradeRequestedEvent, EligibilityResult> decisions = new LinkedHashMap<>();
        List<ProcessedUpgrade> records = new ArrayList<>();
        for (UpgradeRequestedEvent event : events) {
            if (alreadyStored.contains(event.eventId())) {
                log.info("Skipping duplicate event {}", event.eventId());
                continue;
            }
            EligibilityResult result = eligibilityService.evaluate(event.request());
            decisions.put(event, result);
            // notificationSent: the emails are recorded in the outbox by this transaction or not at all; the
            // repository derives their delivery from the outbox when reading.
            records.add(new ProcessedUpgrade(event.eventId(), event.request().userId(), event.source(),
                    result.eligible() ? ProcessingStatus.ELIGIBLE : ProcessingStatus.INELIGIBLE,
                    result.reasons(), true, clock.instant()));
        }
        if (records.isEmpty()) {
            return List.of();
        }
        Set<String> stored = repository.saveAllIfAbsent(records);
        if (stored.size() < records.size()) {
            log.warn("{} of {} events were stored concurrently by another consumer", records.size() - stored.size(),
                    records.size());
            decisions.keySet().removeIf(event -> !stored.contains(event.eventId()));
        }
        notificationService.notifyDecisions(decisions);
        List<ProcessedUpgrade> storedRecords = records.stream().filter(record -> stored.contains(record.eventId()))
                .toList();
        storedRecords.forEach(record ->
                log.debug("Processed event {} for user {}: {} {}", record.eventId(), record.userId(), record.status(),
                        record.reasons()));
        log.info("Processed a batch of {} events ({} skipped as duplicates)", stored.size(),
                events.size() - stored.size());
        return storedRecords;
    }

    /**
     * The decision first, then its emails, only if this transaction stored the decision: a decision another instance
     * stored first gets no second set of emails, as in {@link #processBatch}. Returns the decision if stored here.
     */
    private List<ProcessedUpgrade> processOnce(UpgradeRequestedEvent event) {
        if (repository.existsByEventId(event.eventId())) {
            log.info("Skipping duplicate event {}", event.eventId());
            return List.of();
        }
        EligibilityResult result = eligibilityService.evaluate(event.request());
        // notificationSent: recorded in the outbox by this transaction or not at all; the repository derives the
        // delivery state from the outbox when reading.
        ProcessedUpgrade processed = new ProcessedUpgrade(
                event.eventId(),
                event.request().userId(),
                event.source(),
                result.eligible() ? ProcessingStatus.ELIGIBLE : ProcessingStatus.INELIGIBLE,
                result.reasons(),
                true,
                clock.instant());

        if (!repository.saveIfAbsent(processed)) {
            log.warn("Event {} was stored concurrently by another consumer", event.eventId());
            return List.of();
        }
        notificationService.notifyDecision(event, result);
        log.info("Processed event {} for user {}: {} {}", event.eventId(), processed.userId(),
                processed.status(), processed.reasons());
        return List.of(processed);
    }

    /** Called once the transaction has committed: a rolled-back decision is never counted. */
    private void count(List<ProcessedUpgrade> committed) {
        if (committed != null) {
            committed.forEach(record -> decisions.get(record.status()).increment());
        }
    }
}
