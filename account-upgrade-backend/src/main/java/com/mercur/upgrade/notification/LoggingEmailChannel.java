package com.mercur.upgrade.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Simulated delivery: logs the email. Replace with an SES/SMTP adapter to send real mail. */
@Component
public class LoggingEmailChannel implements EmailChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailChannel.class);

    @Override
    public void deliver(EmailMessage message, String idempotencyKey) {
        log.info("[EMAIL] to {} {} | {} | {} (key {})", message.role(), message.recipient(), message.subject(),
                message.body(), idempotencyKey);
    }
}
