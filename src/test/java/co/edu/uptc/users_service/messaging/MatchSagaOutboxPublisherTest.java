package co.edu.uptc.users_service.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchSagaOutboxPublisherTest {
    private final MatchSagaOutboxStore outbox = mock(MatchSagaOutboxStore.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void marksPublishedOnlyAfterBrokerAcknowledges() {
        ClaimedMatchSagaOutcome event = claimed(1);
        when(outbox.claimPending(10, 120)).thenReturn(List.of(event));
        when(outbox.markPublished(event.id(), event.claimToken())).thenReturn(true);
        when(kafkaTemplate.send(eq(MatchFinishedListener.OUTCOME_TOPIC), eq("match-1"), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture(null));
        MatchSagaOutboxPublisher publisher = publisher();

        publisher.publishPending();

        verify(outbox).markPublished(event.id(), event.claimToken());
        assertEquals(1.0, meterRegistry.counter("match_saga_outbox_published").count());
    }

    @Test
    void keepsFailedPublicationPendingAndSchedulesBackoff() {
        ClaimedMatchSagaOutcome event = claimed(2);
        when(outbox.claimPending(10, 120)).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq(MatchFinishedListener.OUTCOME_TOPIC), eq("match-1"), eq("{}")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        MatchSagaOutboxPublisher publisher = publisher();

        publisher.publishPending();

        verify(outbox).scheduleRetry(event.id(), event.claimToken(), 10, "ExecutionException");
        assertEquals(1.0, meterRegistry.counter("match_saga_outbox_publish_failures").count());
    }

    @Test
    void publishesPendingConfirmationAfterKafkaRecovers() {
        ClaimedMatchSagaOutcome event = claimed(2);
        when(outbox.claimPending(10, 120)).thenReturn(List.of(event), List.of(event));
        when(kafkaTemplate.send(eq(MatchFinishedListener.OUTCOME_TOPIC), eq("match-1"), eq("{}")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")),
                        CompletableFuture.completedFuture(null));
        when(outbox.markPublished(event.id(), event.claimToken())).thenReturn(true);
        MatchSagaOutboxPublisher publisher = publisher();

        publisher.publishPending();
        publisher.publishPending();

        verify(outbox).scheduleRetry(event.id(), event.claimToken(), 10, "ExecutionException");
        verify(outbox).markPublished(event.id(), event.claimToken());
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(
                eq(MatchFinishedListener.OUTCOME_TOPIC), eq("match-1"), eq("{}"));
    }

    @Test
    void acceptedSendWithFailedOutboxUpdateRemainsRetryable() {
        ClaimedMatchSagaOutcome event = claimed(1);
        when(outbox.claimPending(10, 120)).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq(MatchFinishedListener.OUTCOME_TOPIC), eq("match-1"), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outbox.markPublished(event.id(), event.claimToken()))
                .thenThrow(new IllegalStateException("database unavailable"));
        MatchSagaOutboxPublisher publisher = publisher();

        publisher.publishPending();

        verify(outbox).scheduleRetry(event.id(), event.claimToken(), 5, "IllegalStateException");
    }

    @Test
    void retryDelayGrowsAndHasACap() {
        assertEquals(5, MatchSagaOutboxPublisher.retryDelaySeconds(1));
        assertEquals(10, MatchSagaOutboxPublisher.retryDelaySeconds(2));
        assertEquals(300, MatchSagaOutboxPublisher.retryDelaySeconds(20));
    }

    @Test
    void leaseCoversTheMaximumSequentialBatchAcknowledgementTime() {
        MatchSagaOutboxPublisher publisher = new MatchSagaOutboxPublisher(
                outbox, kafkaTemplate, meterRegistry, 10, 120, 25_000);

        publisher.publishPending();

        verify(outbox).claimPending(10, 260);
    }

    private MatchSagaOutboxPublisher publisher() {
        return new MatchSagaOutboxPublisher(outbox, kafkaTemplate, meterRegistry, 10, 120, 1000);
    }

    private static ClaimedMatchSagaOutcome claimed(int attempts) {
        return new ClaimedMatchSagaOutcome(4, "outcome-event", "match-1", "{}", UUID.randomUUID(), attempts);
    }
}
