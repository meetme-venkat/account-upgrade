package com.mercur.upgrade.processing.impl;

import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.eligibility.EligibilityResult;
import com.mercur.upgrade.eligibility.EligibilityService;
import com.mercur.upgrade.notification.NotificationService;
import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.persistence.ProcessingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mercur.upgrade.TestRequests.NOW;
import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.event;
import static com.mercur.upgrade.TestRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpgradeRequestHandlerImplTest {

    @Mock
    private EligibilityService eligibilityService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProcessedUpgradeRepository repository;

    private UpgradeRequestHandlerImpl processor;

    @BeforeEach
    void setUp() {
        processor = new UpgradeRequestHandlerImpl(eligibilityService, notificationService, repository,
                Clock.fixed(NOW, ZoneOffset.UTC), TransactionOperations.withoutTransaction());
    }

    @Test
    void storesEligibleOutcomeAfterNotifying() {
        UpgradeRequestedEvent event = event(eligible());
        EligibilityResult result = EligibilityResult.fromFailures(List.of());
        when(eligibilityService.evaluate(event.request())).thenReturn(result);
        when(notificationService.notifyDecision(event, result)).thenReturn(true);
        when(repository.saveIfAbsent(any())).thenReturn(true);

        processor.handle(event);

        assertThat(savedRecord()).isEqualTo(new ProcessedUpgrade(
                event.eventId(), "u-1", RequestSource.REALTIME, ProcessingStatus.ELIGIBLE, List.of(), true, NOW));
    }

    @Test
    void storesIneligibleOutcomeWithReasons() {
        UpgradeRequestedEvent event = event(request("Carol", 40, "5"));
        EligibilityResult result = EligibilityResult.fromFailures(List.of("too old", "low balance"));
        when(eligibilityService.evaluate(event.request())).thenReturn(result);
        when(notificationService.notifyDecision(event, result)).thenReturn(true);
        when(repository.saveIfAbsent(any())).thenReturn(true);

        processor.handle(event);

        ProcessedUpgrade saved = savedRecord();
        assertThat(saved.status()).isEqualTo(ProcessingStatus.INELIGIBLE);
        assertThat(saved.reasons()).containsExactly("too old", "low balance");
        assertThat(saved.notificationSent()).isTrue();
    }

    @Test
    void recordsNotificationFailure() {
        UpgradeRequestedEvent event = event(eligible());
        EligibilityResult result = EligibilityResult.fromFailures(List.of());
        when(eligibilityService.evaluate(event.request())).thenReturn(result);
        when(notificationService.notifyDecision(event, result)).thenReturn(false);
        when(repository.saveIfAbsent(any())).thenReturn(true);

        processor.handle(event);

        assertThat(savedRecord().notificationSent()).isFalse();
    }

    @Test
    void skipsAlreadyProcessedEvent() {
        UpgradeRequestedEvent event = event(eligible());
        when(repository.existsByEventId(event.eventId())).thenReturn(true);

        processor.handle(event);

        verifyNoInteractions(eligibilityService, notificationService);
        verify(repository, never()).saveIfAbsent(any());
    }

    @Test
    void storesABatchOfDecisionsThenNotifiesThemTogether() {
        UpgradeRequestedEvent approved = event(eligible());
        UpgradeRequestedEvent declined = event(request("Carol", 40, "5"));
        EligibilityResult ok = EligibilityResult.fromFailures(List.of());
        EligibilityResult tooOld = EligibilityResult.fromFailures(List.of("too old"));
        when(eligibilityService.evaluate(approved.request())).thenReturn(ok);
        when(eligibilityService.evaluate(declined.request())).thenReturn(tooOld);
        when(repository.saveAllIfAbsent(any())).thenReturn(Set.of(approved.eventId(), declined.eventId()));

        processor.handleAll(List.of(approved, declined));

        assertThat(savedBatch()).containsExactly(
                new ProcessedUpgrade(approved.eventId(), "u-1", RequestSource.REALTIME, ProcessingStatus.ELIGIBLE,
                        List.of(), true, NOW),
                new ProcessedUpgrade(declined.eventId(), "u-3", RequestSource.REALTIME, ProcessingStatus.INELIGIBLE,
                        List.of("too old"), true, NOW));
        assertThat(notifiedBatch()).containsExactly(entry(approved, ok), entry(declined, tooOld));
    }

    @Test
    void aBatchSkipsEventsAlreadyStoredAndDuplicatesWithinIt() {
        UpgradeRequestedEvent done = event(eligible());
        UpgradeRequestedEvent fresh = event(request("Carol", 20, "50"));
        EligibilityResult ok = EligibilityResult.fromFailures(List.of());
        when(repository.findStoredEventIds(List.of(done.eventId(), fresh.eventId())))
                .thenReturn(Set.of(done.eventId()));
        when(eligibilityService.evaluate(fresh.request())).thenReturn(ok);
        when(repository.saveAllIfAbsent(any())).thenReturn(Set.of(fresh.eventId()));

        processor.handleAll(List.of(done, fresh, fresh));

        assertThat(savedBatch()).extracting(ProcessedUpgrade::eventId).containsExactly(fresh.eventId());
        assertThat(notifiedBatch()).containsOnlyKeys(fresh);
        verify(eligibilityService, never()).evaluate(done.request());
    }

    @Test
    void aDecisionStoredConcurrentlyByAnotherConsumerIsNotNotifiedAgain() {
        UpgradeRequestedEvent mine = event(eligible());
        UpgradeRequestedEvent theirs = event(request("Carol", 20, "50"));
        when(eligibilityService.evaluate(any())).thenReturn(EligibilityResult.fromFailures(List.of()));
        when(repository.saveAllIfAbsent(any())).thenReturn(Set.of(mine.eventId()));

        processor.handleAll(List.of(mine, theirs));

        assertThat(notifiedBatch()).containsOnlyKeys(mine);
    }

    @Test
    void aBatchOfOnlyProcessedEventsWritesNothing() {
        UpgradeRequestedEvent done = event(eligible());
        when(repository.findStoredEventIds(any())).thenReturn(Set.of(done.eventId()));

        processor.handleAll(List.of(done));
        processor.handleAll(List.of());

        verify(repository, never()).saveAllIfAbsent(any());
        verifyNoInteractions(eligibilityService, notificationService);
    }

    private List<ProcessedUpgrade> savedBatch() {
        ArgumentCaptor<List<ProcessedUpgrade>> captor = ArgumentCaptor.captor();
        verify(repository).saveAllIfAbsent(captor.capture());
        return captor.getValue();
    }

    private Map<UpgradeRequestedEvent, EligibilityResult> notifiedBatch() {
        ArgumentCaptor<Map<UpgradeRequestedEvent, EligibilityResult>> captor = ArgumentCaptor.captor();
        verify(notificationService).notifyDecisions(captor.capture());
        return captor.getValue();
    }

    private ProcessedUpgrade savedRecord() {
        ArgumentCaptor<ProcessedUpgrade> captor = ArgumentCaptor.forClass(ProcessedUpgrade.class);
        verify(repository).saveIfAbsent(captor.capture());
        return captor.getValue();
    }
}
