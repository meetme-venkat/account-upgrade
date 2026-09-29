package com.mercur.upgrade.notification;

import java.time.Instant;

/**
 * A simulated email.
 *
 * @param eventId   upgrade event that triggered the email
 * @param role      recipient role
 * @param recipient user id for {@link RecipientRole#USER} (the request carries no user email),
 *                  email address for {@link RecipientRole#PARENT}
 */
public record EmailMessage(String eventId, RecipientRole role, String recipient, String subject, String body,
                           Instant createdAt) {
}
