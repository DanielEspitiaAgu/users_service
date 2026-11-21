package co.edu.uptc.users_service.messaging;

import co.edu.uptc.users_service.application.port.in.StatsUseCase;
import co.edu.uptc.users_service.dto.ResultDTO;
import co.edu.uptc.users_service.dto.ResultResponseDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchFinishedListenerTest {
    private final StatsUseCase statsUseCase = mock(StatsUseCase.class);
    private final MatchSagaOutboxStore outbox = mock(MatchSagaOutboxStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MatchFinishedListener listener = new MatchFinishedListener(
            statsUseCase, objectMapper, outbox, meterRegistry);

    @Test
    void registersEachPlayerAndQueuesCorrelatedConfirmation() throws Exception {
        when(statsUseCase.registerResult(eq("player-a"), any(ResultDTO.class)))
                .thenReturn(new ResultResponseDTO("match-1", "case-1", "VERSUS", true,
                        100, false, null, List.of()));
        when(statsUseCase.registerResult(eq("player-b"), any(ResultDTO.class)))
                .thenReturn(new ResultResponseDTO("match-1", "case-1", "VERSUS", false,
                        0, false, null, List.of()));
        listener.onMatchFinished("""
                {"eventId":"event-1","correlationId":"corr-1","occurredAt":"2026-11-18T12:00:00Z",
                 "payload":{"matchId":"match-1","caseId":"case-1","mode":"VERSUS","timeSeconds":42,
                 "results":[
                   {"userId":"player-a","won":true,"xpDelta":100,"difficulty":null},
                   {"userId":"player-b","won":false,"xpDelta":0,"difficulty":null}
                 ]}}
                """);

        verify(statsUseCase).registerResult(eq("player-a"), any(ResultDTO.class));
        verify(statsUseCase).registerResult(eq("player-b"), any(ResultDTO.class));
        ArgumentCaptor<String> outcomePayload = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("event-1"), any(String.class), eq("match-1"), eq("corr-1"),
                any(String.class), outcomePayload.capture());
        JsonNode outcome = objectMapper.readTree(outcomePayload.getValue());
        assertEquals("corr-1", outcome.path("correlationId").asText());
        assertEquals("CONFIRMED", outcome.path("payload").path("status").asText());
        assertEquals(1.0, meterRegistry.counter("match_finished_processed",
                "application", "usuarios-service", "mode", "VERSUS").count());
    }

    @Test
    void duplicateDeliveryStoresAnIdempotentOutboxRequest() throws Exception {
        when(statsUseCase.registerResult(eq("player-a"), any(ResultDTO.class)))
                .thenReturn(new ResultResponseDTO("match-2", "case-1", "CLASSIC", true,
                        50, true, null, List.of()));
        String event = """
                {"eventId":"event-2","correlationId":"corr-2","occurredAt":"2026-11-18T12:00:00Z",
                 "payload":{"matchId":"match-2","caseId":"case-1","mode":"CLASSIC","timeSeconds":42,
                 "results":[{"userId":"player-a","won":true,"xpDelta":null,"difficulty":"EASY"}]}}
                """;

        listener.onMatchFinished(event);
        listener.onMatchFinished(event);

        verify(statsUseCase, org.mockito.Mockito.times(2))
                .registerResult(eq("player-a"), any(ResultDTO.class));
        ArgumentCaptor<String> outcomes = ArgumentCaptor.forClass(String.class);
        verify(outbox, org.mockito.Mockito.times(2)).enqueue(eq("event-2"), any(String.class),
                eq("match-2"), eq("corr-2"), any(String.class), outcomes.capture());
        JsonNode outcome = objectMapper.readTree(outcomes.getValue());
        assertEquals("corr-2", outcome.path("correlationId").asText());
        assertEquals("CONFIRMED", outcome.path("payload").path("status").asText());
    }

}
