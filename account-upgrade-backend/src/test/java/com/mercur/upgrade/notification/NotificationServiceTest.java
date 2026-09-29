package com.mercur.upgrade.notification;

import com.mercur.upgrade.eligibility.EligibilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static com.mercur.upgrade.TestRequests.NOW;
import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.eligibleWithParent;
import static com.mercur.upgrade.TestRequests.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

    private List<EmailMessage> sentMessages(int expectedCount) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(expectedCount)).send(captor.capture());
        return captor.getAllValues();
    }
}
