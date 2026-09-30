package com.mercur.upgrade.messaging.kafka.impl;

import com.mercur.upgrade.common.UpgradeRequestedEvent;
import com.mercur.upgrade.messaging.MessagingProperties;
import com.mercur.upgrade.messaging.PublishException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.mercur.upgrade.TestRequests.eligible;
import static com.mercur.upgrade.TestRequests.eligibleWithParent;
import static com.mercur.upgrade.TestRequests.event;
import static com.mercur.upgrade.TestRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPublisherImplTest {

    private static final String TOPIC = "upgrade-requests";

    private final UpgradeRequestedEvent first = event(eligible());
    private final UpgradeRequestedEvent second = event(eligibleWithParent());
    private final UpgradeRequestedEvent third = event(request("C", 20, "40"));

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private EventPublisherImpl publisher;

    @BeforeEach
    void setUp() {
        publisher = new EventPublisherImpl(kafkaTemplate, JsonMapper.builder().build(),
                new MessagingProperties(4, new MessagingProperties.Topics(TOPIC, TOPIC + "-dlq")));
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void publishWaitsForTheAcknowledgement() {
        when(kafkaTemplate.send(eq(TOPIC), eq("u-1"), anyString())).thenReturn(acknowledged());

        publisher.publish(first);
    }

    @Test
    void publishFailsWhenKafkaDoesNotAcknowledge() {
        when(kafkaTemplate.send(eq(TOPIC), eq("u-1"), anyString())).thenReturn(failed());

        assertThatThrownBy(() -> publisher.publish(first))
                .isInstanceOf(PublishException.class)
                .hasMessage("Kafka did not acknowledge event evt-u-1");
    }

    @Test
    void publishAllSendsEveryEventInOrderKeyedByUser() {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString())).thenReturn(acknowledged());

        Map<String, PublishException> failures = publisher.publishAll(List.of(first, second, third));

        assertThat(failures).isEmpty();
        InOrder order = inOrder(kafkaTemplate);
        order.verify(kafkaTemplate).send(eq(TOPIC), eq("u-1"), anyString());
        order.verify(kafkaTemplate).send(eq(TOPIC), eq("u-2"), anyString());
        order.verify(kafkaTemplate).send(eq(TOPIC), eq("u-3"), anyString());
    }

    @Test
    void publishAllDoesNotWaitForOneAcknowledgementBeforeSendingTheNext() {
        // The first event is acknowledged only when the second is sent: waiting for it first would time out.
        CompletableFuture<SendResult<String, String>> firstAck = new CompletableFuture<>();
        when(kafkaTemplate.send(eq(TOPIC), eq("u-1"), anyString())).thenReturn(firstAck);
        when(kafkaTemplate.send(eq(TOPIC), eq("u-2"), anyString())).thenAnswer(invocation -> {
            firstAck.complete(null);
            return acknowledged();
        });

        long start = System.nanoTime();
        Map<String, PublishException> failures = publisher.publishAll(List.of(first, second));

        assertThat(failures).isEmpty();
        assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }

    @Test
    void publishAllReportsEachFailedEventAndKeepsTheOthers() {
        when(kafkaTemplate.send(eq(TOPIC), eq("u-1"), anyString())).thenReturn(failed());
        when(kafkaTemplate.send(eq(TOPIC), eq("u-2"), anyString())).thenThrow(new KafkaException("buffer full"));
        when(kafkaTemplate.send(eq(TOPIC), eq("u-3"), anyString())).thenReturn(acknowledged());

        Map<String, PublishException> failures = publisher.publishAll(List.of(first, second, third));

        assertThat(failures).containsOnlyKeys("evt-u-1", "evt-u-2");
        assertThat(failures.get("evt-u-1")).hasMessage("Kafka did not acknowledge event evt-u-1");
        assertThat(failures.get("evt-u-2")).hasMessage("Kafka did not accept event evt-u-2");
    }

    @Test
    void publishAllInterruptedReportsTheUnconfirmedEventsAndKeepsTheInterrupt() {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();

        Map<String, PublishException> failures = publisher.publishAll(List.of(first, second));

        assertThat(failures).containsOnlyKeys("evt-u-1", "evt-u-2");
        assertThat(failures.values()).allSatisfy(failure -> assertThat(failure.getMessage()).startsWith("Interrupted"));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        return CompletableFuture.failedFuture(new KafkaException("not enough replicas"));
    }
}
