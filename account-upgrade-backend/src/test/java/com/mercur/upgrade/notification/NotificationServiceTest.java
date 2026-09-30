package com.mercur.upgrade.notification;

import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.eligibility.EligibilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mercur.upgrade.TestRequests.NOW;
import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.eligibleWithParent;
import static com.mercur.upgrade.TestRequests.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final EligibilityResult APPROVED = EligibilityResult.fromFailures(List.of());

    @Mock
    private EmailSender emailSender;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(emailSender, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void eligibleUserWithoutParentNotifiesOnlyTheUser() {
        assertThat(service.notifyDecision(event(eligible()), APPROVED)).isTrue();

        List<EmailMessage> sent = sentMessages(1);
        assertThat(sent.get(0).role()).isEqualTo(RecipientRole.USER);
        assertThat(sent.get(0).recipient()).isEqualTo("u-1");
        assertThat(sent.get(0).subject()).isEqualTo("Your account upgrade is approved");
    }

    @Test
    void eligibleUserWithParentNotifiesUserAndParent() {
        assertThat(service.notifyDecision(event(eligibleWithParent()), APPROVED)).isTrue();

        List<EmailMessage> sent = sentMessages(2);
        assertThat(sent).extracting(EmailMessage::role).containsExactly(RecipientRole.USER, RecipientRole.PARENT);
        assertThat(sent.get(1).recipient()).isEqualTo("parent@example.com");
    }

    @Test
    void ineligibleUserReceivesReasonsAndParentIsNotNotified() {
        EligibilityResult declined = EligibilityResult.fromFailures(List.of("Age too high", "Balance too low"));

        assertThat(service.notifyDecision(event(eligibleWithParent()), declined)).isTrue();

        List<EmailMessage> sent = sentMessages(1);
        assertThat(sent.get(0).role()).isEqualTo(RecipientRole.USER);
        assertThat(sent.get(0).body()).contains("Age too high; Balance too low");
    }

    @Test
    void reportsFailureButStillAttemptsRemainingRecipients() {
        // First send (the user) fails, second send (the parent) succeeds.
        doThrow(new IllegalStateException("smtp down")).doNothing().when(emailSender).send(any());

        assertThat(service.notifyDecision(event(eligibleWithParent()), APPROVED)).isFalse();

        assertThat(sentMessages(2)).extracting(EmailMessage::role)
                .containsExactly(RecipientRole.USER, RecipientRole.PARENT);
    }

    @Test
    void notifiesSeveralDecisionsWithOneSendInOrder() {
        EligibilityResult declined = EligibilityResult.fromFailures(List.of("Balance too low"));
        Map<UpgradeRequestedEvent, EligibilityResult> decisions = new LinkedHashMap<>();
        decisions.put(event(eligibleWithParent()), APPROVED);
        decisions.put(event(eligible()), declined);

        service.notifyDecisions(decisions);

        ArgumentCaptor<List<EmailMessage>> captor = ArgumentCaptor.captor();
        verify(emailSender).sendAll(captor.capture());
        assertThat(captor.getValue()).extracting(EmailMessage::recipient, EmailMessage::role).containsExactly(
                tuple("u-2", RecipientRole.USER), tuple("parent@example.com", RecipientRole.PARENT),
                tuple("u-1", RecipientRole.USER));
        assertThat(captor.getValue().get(2).body()).contains("Balance too low");
        verify(emailSender, never()).send(any());
    }

    @Test
    void notifiesNothingForNoDecisions() {
        service.notifyDecisions(Map.of());

        verifyNoInteractions(emailSender);
    }

    @Test
    void aFailureNotifyingSeveralDecisionsIsThrown() {
        // Unlike a single decision's: the batch is then rolled back and processed one event at a time.
        doThrow(new IllegalStateException("smtp down")).when(emailSender).sendAll(any());

        assertThatThrownBy(() -> service.notifyDecisions(Map.of(event(eligible()), APPROVED)))
                .isInstanceOf(IllegalStateException.class);
    }

    private List<EmailMessage> sentMessages(int expectedCount) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(expectedCount)).send(captor.capture());
        return captor.getAllValues();
    }
}
