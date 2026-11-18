package co.edu.uptc.users_service.messaging;

import java.util.List;

public record MatchSagaOutcome(
        String eventId,
        String correlationId,
        String occurredAt,
        Payload payload) {

    public record Payload(String matchId, String status, List<AppliedResult> results) {}

    public record AppliedResult(
            String userId, boolean won, int xpDelta, boolean firstWinRewarded) {}
}
