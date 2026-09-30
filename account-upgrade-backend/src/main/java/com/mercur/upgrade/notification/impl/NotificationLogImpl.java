package com.mercur.upgrade.notification.impl;

import com.mercur.upgrade.notification.EmailMessage;
import com.mercur.upgrade.notification.NotificationLog;
import com.mercur.upgrade.notification.jpa.OutboxEmailEntity;
import com.mercur.upgrade.notification.jpa.OutboxEmailJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Recently delivered emails from the outbox, shared by all instances. */
@Component
public class NotificationLogImpl implements NotificationLog {

    private final OutboxEmailJpaRepository outbox;
    private final int capacity;

    public NotificationLogImpl(OutboxEmailJpaRepository outbox,
                               @Value("${upgrade.notification.outbox-capacity:1000}") int capacity) {
        this.outbox = outbox;
        this.capacity = capacity;
    }

    @Override
    public List<EmailMessage> sentMessages() {
        List<EmailMessage> newestFirst = outbox.findBySentAtIsNotNullOrderByIdDesc(Limit.of(capacity)).stream()
                .map(OutboxEmailEntity::toMessage)
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(newestFirst);
        return newestFirst;
    }
}
