package co.edu.uptc.users_service.messaging;

import java.util.List;
import java.util.UUID;

public interface MatchSagaOutboxStore {
    void enqueue(String sourceEventId, String sourceFingerprint, String matchId,
            String correlationId, String eventId, String payload);

    List<ClaimedMatchSagaOutcome> claimPending(int limit, int leaseSeconds);

    boolean markPublished(long id, UUID claimToken);

    boolean scheduleRetry(long id, UUID claimToken, int delaySeconds, String errorType);

    long countPending();
}
