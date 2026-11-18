package co.edu.uptc.users_service.messaging;

import java.util.List;

public record MatchFinishedEvent(
        String eventId,
        String correlationId,
        String occurredAt,
        Payload payload) {

    public record Payload(
            String matchId,
            String caseId,
            String mode,
            Integer timeSeconds,
            List<PlayerResult> results) {}

    public record PlayerResult(
            String userId,
            Boolean won,
            Integer xpDelta,
            String difficulty) {}
}
