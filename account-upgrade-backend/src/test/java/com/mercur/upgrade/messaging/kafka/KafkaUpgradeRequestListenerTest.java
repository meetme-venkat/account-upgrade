package com.mercur.upgrade.messaging.kafka;

import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.UpgradeRequestHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.listener.BatchListenerFailedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.eligibleWithParent;
import static com.mercur.upgrade.TestRequests.event;
import static com.mercur.upgrade.TestRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A poll's records are handled as one batch; when the batch fails they are handled one at a time, and the first
 * one that still fails is reported with its record, so the error handler retries or dead-letters just that record.
 */
@ExtendWith(MockitoExtension.class)
class KafkaUpgradeRequestListenerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UpgradeRequestedEvent first = event(eligible());
    private final UpgradeRequestedEvent second = event(eligibleWithParent());
    private final UpgradeRequestedEvent third = event(request("Carol", 20, "50"));

    @Mock
    private UpgradeRequestHandler handler;

    private KafkaUpgradeRequestListener listener;

    @BeforeEach
    void setUp() {
        listener = new KafkaUpgradeRequestListener(handler, JSON);
    }

    @Test
    void handlesAPollAsOneBatch() {
        listener.onMessages(records(json(first), json(second), json(third)));

        verify(handler).handleAll(List.of(first, second, third));
        verify(handler, never()).handle(any());
    }

    @Test
    void aFailedBatchIsHandledOneEventAtATime() {
        doThrow(new IllegalStateException("deadlock")).when(handler).handleAll(any());

        listener.onMessages(records(json(first), json(second)));

        InOrder order = inOrder(handler);
        order.verify(handler).handleAll(List.of(first, second));
        order.verify(handler).handle(first);
        order.verify(handler).handle(second);
    }

    @Test
    void theFirstEventThatFailsOnItsOwnIsReportedWithItsRecord() {
        List<ConsumerRecord<String, String>> records = records(json(first), json(second), json(third));
        IllegalStateException failure = new IllegalStateException("database down");
        doThrow(failure).when(handler).handleAll(any());
        // Lenient: handle(first) is called too, with no stubbing (strict stubs would reject that call).
        lenient().doThrow(failure).when(handler).handle(second);

        assertThatThrownBy(() -> listener.onMessages(records))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getRecord()).isSameAs(records.get(1));
                    assertThat(e.getCause()).isSameAs(failure);
                })
                .hasMessageContaining(second.eventId());
        verify(handler).handle(first);
        verify(handler, never()).handle(third);
    }

    @Test
    void anUnreadableRecordIsReportedAfterTheRecordsBeforeItAreHandled() {
        List<ConsumerRecord<String, String>> records = records(json(first), json(second), "not json", json(third));

        assertThatThrownBy(() -> listener.onMessages(records))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getRecord()).isSameAs(records.get(2));
                    assertThat(e.getCause()).isInstanceOf(JacksonException.class);
                })
                .hasMessageContaining("upgrade-requests-0@2");
        verify(handler).handleAll(List.of(first, second));
    }

    @Test
    void anUnreadableFirstRecordIsReportedWithoutHandlingAnything() {
        List<ConsumerRecord<String, String>> records = records("{", json(first));

        assertThatThrownBy(() -> listener.onMessages(records))
                .isInstanceOfSatisfying(BatchListenerFailedException.class,
                        e -> assertThat(e.getRecord()).isSameAs(records.get(0)));
        verifyNoInteractions(handler);
    }

    private static String json(UpgradeRequestedEvent event) {
        return JSON.writeValueAsString(event);
    }

    /** Records at offsets 0, 1, 2... of partition 0. */
    private static List<ConsumerRecord<String, String>> records(String... values) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            records.add(new ConsumerRecord<>("upgrade-requests", 0, i, "key-" + i, values[i]));
        }
        return records;
    }
}
