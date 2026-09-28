package com.mercur.upgrade.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes the mock outbox so simulated emails can be inspected over HTTP. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final InMemoryEmailSender emailSender;

    public NotificationController(InMemoryEmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @GetMapping
    public List<EmailMessage> sentNotifications() {
        return emailSender.sentMessages();
    }
}
