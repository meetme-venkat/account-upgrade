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

import static com.mercur.upgrade.TestRequests.NOW;
import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.eligibleWithParent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

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
    void batchContinuesAfterSingleItemPublishFailure() {
        doThrow(new PublishException("partition full")).doNothing().when(publisher).publish(any());

        BatchIngestionResponse response = service.ingestBatch(List.of(eligible(), eligibleWithParent()));

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.receipts().get(0))
                .isEqualTo(IngestionReceipt.rejected("u-1", RequestSource.BATCH, "partition full"));
        assertThat(response.receipts().get(1).status()).isEqualTo(IngestionReceipt.Status.ACCEPTED);
        assertThat(response.receipts().get(1).source()).isEqualTo(RequestSource.BATCH);
    }
}
