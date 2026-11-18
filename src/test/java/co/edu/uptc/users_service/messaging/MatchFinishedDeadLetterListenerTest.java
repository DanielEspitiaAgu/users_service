package co.edu.uptc.users_service.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchFinishedDeadLetterListenerTest {
    @Test
    void publishesCorrelatedCancellationForDeadLetter() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(kafkaTemplate.send(eq(MatchFinishedListener.OUTCOME_TOPIC), eq("match-dead"), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        MatchFinishedDeadLetterListener listener = new MatchFinishedDeadLetterListener(
                objectMapper, kafkaTemplate, meterRegistry);

        listener.onDeadLetter("""
                {"eventId":"event-dead","correlationId":"corr-dead","occurredAt":"2026-11-18T12:00:00Z",
                 "payload":{"matchId":"match-dead","caseId":"case-1","mode":"CLASSIC",
                 "timeSeconds":42,"results":[]}}
                """);

        var outcome = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(MatchFinishedListener.OUTCOME_TOPIC), eq("match-dead"), outcome.capture());
        JsonNode json = objectMapper.readTree(outcome.getValue());
        assertEquals("corr-dead", json.path("correlationId").asText());
        assertEquals("CANCELLED", json.path("payload").path("status").asText());
        assertEquals(1.0, meterRegistry.counter("match_finished_failed", "application", "usuarios-service").count());
    }
}
