package com.mercur.upgrade.notification;

import com.mercur.upgrade.common.UpgradeRequest;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.eligibility.EligibilityResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and sends the notifications for an eligibility decision:
 * <ul>
 *   <li>eligible: the user, plus the parent when a parent email is present;</li>
 *   <li>ineligible: the user, with the failure reasons.</li>
 * </ul>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final EmailSender emailSender;
    private final Clock clock;

    public NotificationService(EmailSender emailSender, Clock clock) {
        this.emailSender = emailSender;
        this.clock = clock;
    }

    /**
     * Sends all notifications for the decision. Delivery failures are logged, not rethrown,
     * so that a notification problem does not cause the decision to be re-processed (and the
     * other recipients to be notified twice).
     *
     * @return {@code true} if every notification was sent
     */
    public boolean notifyDecision(UpgradeRequestedEvent event, EligibilityResult result) {
        boolean allSent = true;
        for (EmailMessage message : buildMessages(event, result)) {
            try {
                emailSender.send(message);
            } catch (RuntimeException e) {
                allSent = false;
                log.error("Failed to notify {} {} for event {}", message.role(), message.recipient(), event.eventId(), e);
            }
        }
        return allSent;
    }

    List<EmailMessage> buildMessages(UpgradeRequestedEvent event, EligibilityResult result) {
        UpgradeRequest request = event.request();
        String name = displayName(request);
        List<EmailMessage> messages = new ArrayList<>();

        if (result.eligible()) {
            messages.add(message(event, RecipientRole.USER, request.userId(),
                    "Your account upgrade is approved",
                    "Hi %s, good news! Your account is eligible and has been upgraded.".formatted(name)));
            if (request.hasParentEmail()) {
                messages.add(message(event, RecipientRole.PARENT, request.parentEmail(),
                        "Account upgrade approved for %s".formatted(name),
                        "Hello, the account of %s (user %s) has been upgraded.".formatted(name, request.userId())));
            }
        } else {
            messages.add(message(event, RecipientRole.USER, request.userId(),
                    "Your account upgrade request was declined",
                    "Hi %s, we could not upgrade your account for the following reason(s): %s."
                            .formatted(name, String.join("; ", result.reasons()))));
        }
        return messages;
    }

    private EmailMessage message(UpgradeRequestedEvent event, RecipientRole role, String recipient,
                                 String subject, String body) {
        return new EmailMessage(event.eventId(), role, recipient, subject, body, clock.instant());
    }

    private static String displayName(UpgradeRequest request) {
        return request.userName() == null || request.userName().isBlank() ? request.userId() : request.userName();
    }
}
