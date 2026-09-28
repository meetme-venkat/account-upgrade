package com.mercur.upgrade.processing;

import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.eligibility.EligibilityResult;
import com.mercur.upgrade.eligibility.EligibilityService;
import com.mercur.upgrade.messaging.UpgradeRequestHandler;
import com.mercur.upgrade.notification.NotificationService;
import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.persistence.ProcessingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer of the {@code upgrade-requests} topic: eligibility check -> notification -> persistence.
 *
 * <p>Delivery is at-least-once, so events that were already processed are skipped by event id.
 * The in-process claim covers one instance; across instances the repository's unique event id
 * (a primary key in PostgreSQL) is the final guard.
 */
@Service
public class UpgradeRequestProcessor implements UpgradeRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(UpgradeRequestProcessor.class);

    private final EligibilityService eligibilityService;
    private final NotificationService notificationService;
    private final ProcessedUpgradeRepository repository;
    private final Clock clock;
    /** Event ids currently being processed; bounded by the number of consumer threads. */
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    public UpgradeRequestProcessor(EligibilityService eligibilityService,
                                   NotificationService notificationService,
                                   ProcessedUpgradeRepository repository,
                                   Clock clock) {
        this.eligibilityService = eligibilityService;
        this.notificationService = notificationService;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void handle(UpgradeRequestedEvent event) {
        // Claim the event first: without it, two concurrent deliveries of the same event (e.g. after a
        // Kafka rebalance) would both pass the "already processed?" check and notify the user twice.
        if (!inProgress.add(event.eventId())) {
            log.info("Skipping event {}: already being processed by another consumer", event.eventId());
            return;
        }
        try {
            if (repository.existsByEventId(event.eventId())) {
                log.info("Skipping duplicate event {}", event.eventId());
                return;
            }
            process(event);
        } finally {
            inProgress.remove(event.eventId()); // released on failure too, so a retry can process it
        }
    }

    private void process(UpgradeRequestedEvent event) {
        EligibilityResult result = eligibilityService.evaluate(event.request());
        boolean notificationSent = notificationService.notifyDecision(event, result);

        ProcessedUpgrade processed = new ProcessedUpgrade(
                event.eventId(),
                event.request().userId(),
                event.source(),
                result.eligible() ? ProcessingStatus.ELIGIBLE : ProcessingStatus.INELIGIBLE,
                result.reasons(),
                notificationSent,
                clock.instant());

        if (repository.saveIfAbsent(processed)) {
            log.info("Processed event {} for user {}: {} {}", event.eventId(), processed.userId(),
                    processed.status(), processed.reasons());
        } else {
            log.warn("Event {} was stored concurrently by another consumer", event.eventId());
        }
    }
}
