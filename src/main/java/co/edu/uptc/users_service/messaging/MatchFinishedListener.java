package co.edu.uptc.users_service.messaging;

import co.edu.uptc.users_service.application.port.in.StatsUseCase;
import co.edu.uptc.users_service.dto.ResultDTO;
import co.edu.uptc.users_service.dto.ResultResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MatchFinishedListener {
    static final String OUTCOME_TOPIC = "matches.saga.confirmed.v1";

    private final StatsUseCase statsUseCase;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${events.match-finished-topic:matches.finished.v1}",
            groupId = "${events.users-group:usuarios-match-results}")
    @Transactional
    public void onMatchFinished(String rawEvent) throws Exception {
        MatchFinishedEvent event = objectMapper.readValue(rawEvent, MatchFinishedEvent.class);
        validate(event);

        List<MatchSagaOutcome.AppliedResult> applied = new ArrayList<>();
        for (MatchFinishedEvent.PlayerResult player : event.payload().results()) {
            ResultResponseDTO result = statsUseCase.registerResult(
                    player.userId(),
                    new ResultDTO(
                            event.payload().matchId(),
                            event.payload().caseId(),
                            event.payload().mode(),
                            player.won(),
                            event.payload().timeSeconds(),
                            player.difficulty(),
                            player.xpDelta()));
            applied.add(new MatchSagaOutcome.AppliedResult(
                    player.userId(), player.won(), result.xpDelta(), result.firstWinRewarded()));
        }

        MatchSagaOutcome outcome = new MatchSagaOutcome(
                UUID.randomUUID().toString(),
                event.correlationId(),
                Instant.now().toString(),
                new MatchSagaOutcome.Payload(event.payload().matchId(), "CONFIRMED", applied));
        kafkaTemplate.send(OUTCOME_TOPIC, event.payload().matchId(),
                objectMapper.writeValueAsString(outcome)).get();
        meterRegistry.counter("match_finished_processed",
                "application", "usuarios-service",
                "mode", event.payload().mode()).increment();
        System.out.printf("MatchFinished procesado eventId=%s correlationId=%s matchId=%s jugadores=%d%n",
                event.eventId(), event.correlationId(), event.payload().matchId(), applied.size());
    }

    private static void validate(MatchFinishedEvent event) {
        if (event == null || blank(event.eventId()) || blank(event.correlationId())
                || event.payload() == null || blank(event.payload().matchId())
                || blank(event.payload().caseId()) || blank(event.payload().mode())
                || event.payload().timeSeconds() == null || event.payload().timeSeconds() < 0
                || event.payload().results() == null || event.payload().results().isEmpty()) {
            throw new IllegalArgumentException("MatchFinished no cumple el contrato requerido");
        }
        for (MatchFinishedEvent.PlayerResult result : event.payload().results()) {
            if (result == null || blank(result.userId()) || result.won() == null
                    || ("CLASSIC".equals(event.payload().mode()) && blank(result.difficulty()))
                    || (!"CLASSIC".equals(event.payload().mode()) && result.xpDelta() == null)) {
                throw new IllegalArgumentException("MatchFinished contiene un resultado inválido");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
