package com.mercur.upgrade.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes recently sent (simulated) emails so they can be inspected over HTTP. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationLog notificationLog;

    public NotificationController(NotificationLog notificationLog) {
        this.notificationLog = notificationLog;
    }

    @GetMapping
    public List<EmailMessage> sentNotifications() {
        return notificationLog.sentMessages();
    }
}
