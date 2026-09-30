package com.mercur.upgrade.notification.impl;

import com.mercur.upgrade.IntegrationTestSupport;
import com.mercur.upgrade.notification.EmailMessage;
import com.mercur.upgrade.notification.RecipientRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.stream.IntStream;

import static com.mercur.upgrade.TestRequests.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The outbox writer, one message at a time and many at once. */
class EmailSenderImplTest extends IntegrationTestSupport {

    @Autowired
    private EmailSenderImpl outbox;

    private static EmailMessage message(String eventId, RecipientRole role) {
        return new EmailMessage(eventId, role, eventId + "@example.com", "subject " + eventId, "body " + eventId, NOW);
    }

    @Test
    void recordsAMessageForDelivery() {
        outbox.send(message("e1", RecipientRole.USER));

        assertThat(jdbc.sql("SELECT recipient FROM notification_outbox WHERE sent_at IS NULL").query(String.class).list())
                .containsExactly("e1@example.com");
    }

    @Test
    void refusesASecondMessageForTheSameEventAndRole() {
        outbox.send(message("e1", RecipientRole.USER));

        assertThatThrownBy(() -> outbox.send(message("e1", RecipientRole.USER)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("notification_outbox")).isEqualTo(1);
    }

    @Test
    void recordsManyMessagesAtOnceInOrder() {
        outbox.sendAll(List.of(message("e1", RecipientRole.USER), message("e1", RecipientRole.PARENT),
                message("e2", RecipientRole.USER), message("e3", RecipientRole.USER)));

        assertThat(jdbc.sql("SELECT event_id || '/' || role FROM notification_outbox ORDER BY id")
                .query(String.class).list())
                .containsExactly("e1/USER", "e1/PARENT", "e2/USER", "e3/USER");
        assertThat(jdbc.sql("SELECT body FROM notification_outbox WHERE event_id = 'e3'").query(String.class).single())
                .isEqualTo("body e3");
    }

    @Test
    void recordsMoreMessagesThanOneIdBlockHolds() {
        // Ids are reserved 50 at a time from the column's sequence: more rows than that need several blocks.
        int size = 123;

        outbox.sendAll(IntStream.range(0, size).mapToObj(i -> message("e" + i, RecipientRole.USER)).toList());
        outbox.sendAll(List.of());

        assertThat(count("notification_outbox")).isEqualTo(size);
        assertThat(jdbc.sql("SELECT count(DISTINCT id) FROM notification_outbox").query(Long.class).single())
                .isEqualTo(size);
    }
}
