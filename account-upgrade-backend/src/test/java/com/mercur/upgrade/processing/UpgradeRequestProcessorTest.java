package com.mercur.upgrade.processing;

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

import static com.mercur.upgrade.TestRequests.NOW;
import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.event;
import static com.mercur.upgrade.TestRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpgradeRequestProcessorTest {

    @Mock
    private EligibilityService eligibilityService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProcessedUpgradeRepository repository;

    private UpgradeRequestProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new UpgradeRequestProcessor(eligibilityService, notificationService, repository,
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

    private ProcessedUpgrade savedRecord() {
        ArgumentCaptor<ProcessedUpgrade> captor = ArgumentCaptor.forClass(ProcessedUpgrade.class);
        verify(repository).saveIfAbsent(captor.capture());
        return captor.getValue();
    }
}
