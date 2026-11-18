package co.edu.uptc.users_service.messaging;

import co.edu.uptc.users_service.messaging.MatchFinishedEvent.Payload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MatchFinishedDeadLetterListener {
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${events.match-finished-dlq:matches.finished.dlq}",
            groupId = "${events.users-dlq-group:usuarios-match-results-dlq}",
            containerFactory = "kafkaDltListenerContainerFactory")
    public void onDeadLetter(String rawEvent) throws Exception {
        MatchFinishedEvent event = objectMapper.readValue(rawEvent, MatchFinishedEvent.class);
        if (event == null || event.payload() == null || event.payload().matchId() == null
                || event.correlationId() == null) {
            throw new IllegalArgumentException("No se puede correlacionar el evento de DLQ");
        }
        MatchSagaOutcome cancelled = new MatchSagaOutcome(
                UUID.randomUUID().toString(), event.correlationId(), Instant.now().toString(),
                new MatchSagaOutcome.Payload(event.payload().matchId(), "CANCELLED", java.util.List.of()));
        kafkaTemplate.send(MatchFinishedListener.OUTCOME_TOPIC, event.payload().matchId(),
                objectMapper.writeValueAsString(cancelled)).get();
        meterRegistry.counter("match_finished_failed", "application", "usuarios-service").increment();
        System.err.printf("MatchFinished agotó reintentos eventId=%s correlationId=%s matchId=%s status=CANCELLED%n",
                event.eventId(), event.correlationId(), event.payload().matchId());
    }
}
