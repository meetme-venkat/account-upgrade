package com.mercur.upgrade.notification;

import java.util.List;

/** Read access to recently sent notifications, for {@code GET /api/notifications}. */
public interface NotificationLog {

    /** The most recent sent emails, oldest first. */
    List<EmailMessage> sentMessages();
}
