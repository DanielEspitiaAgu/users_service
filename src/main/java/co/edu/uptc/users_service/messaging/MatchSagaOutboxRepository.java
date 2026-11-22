package co.edu.uptc.users_service.messaging;

import co.edu.uptc.users_service.exception.ResultConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MatchSagaOutboxRepository implements MatchSagaOutboxStore {
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String sourceEventId, String sourceFingerprint, String matchId,
            String correlationId, String eventId, String payload) {
        jdbcTemplate.update("""
                INSERT INTO match_saga_outbox (
                    event_id, source_event_id, source_fingerprint, match_id, correlation_id, payload)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_event_id) DO NOTHING
                """, eventId, sourceEventId, sourceFingerprint, matchId, correlationId, payload);

        String existingFingerprint = jdbcTemplate.queryForObject(
                "SELECT source_fingerprint FROM match_saga_outbox WHERE source_event_id = ? FOR UPDATE",
                String.class, sourceEventId);
        if (!sourceFingerprint.equals(existingFingerprint)) {
            throw new ResultConflictException();
        }
        jdbcTemplate.update("""
                UPDATE match_saga_outbox
                SET status = 'PENDING', published_at = NULL, next_attempt_at = CURRENT_TIMESTAMP,
                    last_error = NULL, claimed_until = NULL, claim_token = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE source_event_id = ? AND status = 'PUBLISHED'
                """, sourceEventId);
    }

    @Override
    public List<ClaimedMatchSagaOutcome> claimPending(int limit, int leaseSeconds) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        UUID claimToken = UUID.randomUUID();
        return transaction.execute(status -> jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT id
                    FROM match_saga_outbox
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= CURRENT_TIMESTAMP
                      AND (claimed_until IS NULL OR claimed_until < CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE match_saga_outbox AS outbox
                SET claimed_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    claim_token = ?,
                    attempts = outbox.attempts + 1,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE outbox.id = candidates.id
                RETURNING outbox.id, outbox.event_id, outbox.match_id, outbox.payload,
                          outbox.claim_token, outbox.attempts
                """, (row, rowNumber) -> new ClaimedMatchSagaOutcome(
                row.getLong("id"), row.getString("event_id"), row.getString("match_id"),
                row.getString("payload"), row.getObject("claim_token", UUID.class),
                row.getInt("attempts")), limit, leaseSeconds, claimToken));
    }

    @Override
    public boolean markPublished(long id, UUID claimToken) {
        return jdbcTemplate.update("""
                UPDATE match_saga_outbox
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                    claimed_until = NULL, claim_token = NULL, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING' AND claim_token = ?
                """, id, claimToken) == 1;
    }

    @Override
    public boolean scheduleRetry(long id, UUID claimToken, int delaySeconds, String errorType) {
        return jdbcTemplate.update("""
                UPDATE match_saga_outbox
                SET next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    claimed_until = NULL, claim_token = NULL, last_error = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING' AND claim_token = ?
                """, delaySeconds, errorType, id, claimToken) == 1;
    }

    @Override
    public long countPending() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM match_saga_outbox WHERE status = 'PENDING'", Long.class);
        return count == null ? 0 : count;
    }
}
