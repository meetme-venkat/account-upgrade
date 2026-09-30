package com.mercur.upgrade.ingestion;

import com.mercur.upgrade.common.RequestSource;
import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.EventPublisher;
import com.mercur.upgrade.messaging.PublishException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.mercur.upgrade.TestRequests.NOW;
import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.eligibleWithParent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private EventPublisher publisher;

    private IngestionService service;

    @BeforeEach
    void setUp() {
        service = new IngestionService(publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void realtimeRequestIsPublishedAsRealtimeEvent() {
        IngestionReceipt receipt = service.ingestRealtime(eligible());

        ArgumentCaptor<UpgradeRequestedEvent> captor = ArgumentCaptor.forClass(UpgradeRequestedEvent.class);
        verify(publisher).publish(captor.capture());
        UpgradeRequestedEvent event = captor.getValue();
        assertThat(event.source()).isEqualTo(RequestSource.REALTIME);
        assertThat(event.receivedAt()).isEqualTo(NOW);
        assertThat(event.request()).isEqualTo(eligible());
        assertThat(receipt).isEqualTo(IngestionReceipt.accepted("u-1", event.eventId(), RequestSource.REALTIME));
    }

    @Test
    void realtimePublishFailurePropagates() {
        doThrow(new PublishException("full")).when(publisher).publish(any());

        assertThatThrownBy(() -> service.ingestRealtime(eligible())).isInstanceOf(PublishException.class);
    }

    @Test
    void batchIsPublishedInOneCallInInputOrder() {
        when(publisher.publishAll(any())).thenReturn(Map.of());

        BatchIngestionResponse response = service.ingestBatch(List.of(eligible(), eligibleWithParent()));

        ArgumentCaptor<List<UpgradeRequestedEvent>> captor = ArgumentCaptor.captor();
        verify(publisher).publishAll(captor.capture());
        verify(publisher, never()).publish(any());
        List<UpgradeRequestedEvent> events = captor.getValue();
        assertThat(events).extracting(event -> event.request().userId()).containsExactly("u-1", "u-2");
        assertThat(events).extracting(UpgradeRequestedEvent::source).containsOnly(RequestSource.BATCH);
        assertThat(response.accepted()).isEqualTo(2);
        assertThat(response.receipts()).extracting(IngestionReceipt::eventId)
                .containsExactly(events.get(0).eventId(), events.get(1).eventId());
    }

    @Test
    void batchContinuesAfterSingleItemPublishFailure() {
        when(publisher.publishAll(any())).thenAnswer(invocation -> {
            List<UpgradeRequestedEvent> events = invocation.getArgument(0);
            return Map.of(events.get(0).eventId(), new PublishException("partition full"));
        });

        BatchIngestionResponse response = service.ingestBatch(List.of(eligible(), eligibleWithParent()));

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.receipts().get(0))
                .isEqualTo(IngestionReceipt.rejected("u-1", RequestSource.BATCH, "partition full"));
        assertThat(response.receipts().get(1).status()).isEqualTo(IngestionReceipt.Status.ACCEPTED);
        assertThat(response.receipts().get(1).source()).isEqualTo(RequestSource.BATCH);
    }

    @Test
    void batchIdempotencyKeyGivesEachItemItsOwnStableEventId() {
        when(publisher.publishAll(any())).thenReturn(Map.of());

        BatchIngestionResponse first = service.ingestBatch(List.of(eligible(), eligibleWithParent()), "key-1");
        BatchIngestionResponse retry = service.ingestBatch(List.of(eligible(), eligibleWithParent()), "key-1");

        assertThat(first.receipts()).extracting(IngestionReceipt::eventId).doesNotHaveDuplicates()
                .isEqualTo(retry.receipts().stream().map(IngestionReceipt::eventId).toList());
    }
}
