package co.edu.uptc.users_service.messaging;

import java.util.UUID;

public record ClaimedMatchSagaOutcome(
        long id, String eventId, String matchId, String payload, UUID claimToken, int attempts) {
}
