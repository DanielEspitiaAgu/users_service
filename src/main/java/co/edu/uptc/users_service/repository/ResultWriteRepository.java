package co.edu.uptc.users_service.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ResultWriteRepository {

    private final JdbcTemplate jdbcTemplate;

    public Long insertIfAbsent(long userId, String matchId, String caseId, String mode,
            String difficulty, boolean won, int timeSeconds, int xpDelta) {
        String sql = "INSERT INTO user_results "
                + "(user_id, match_id, case_id, mode, difficulty, won, time_seconds, xp_delta, "
                + "first_win_rewarded, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, false, CURRENT_TIMESTAMP) "
                + "ON CONFLICT (user_id, match_id) DO NOTHING RETURNING id";
        List<Long> ids = jdbcTemplate.query(sql,
                (result, rowNumber) -> result.getLong("id"),
                userId, matchId, caseId, mode, difficulty, won, timeSeconds, xpDelta);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public boolean claimClassicFirstWin(long userId, String caseId, long resultId, int xpAwarded) {
        String sql = "INSERT INTO classic_case_rewards "
                + "(user_id, case_id, mode, first_result_id, xp_awarded, created_at) "
                + "VALUES (?, ?, 'CLASSIC', ?, ?, CURRENT_TIMESTAMP) "
                + "ON CONFLICT (user_id, case_id, mode) DO NOTHING RETURNING first_result_id";
        List<Long> claims = jdbcTemplate.query(sql,
                (result, rowNumber) -> result.getLong("first_result_id"),
                userId, caseId, resultId, xpAwarded);
        return !claims.isEmpty();
    }

    public void updateAppliedReward(long resultId, int xpDelta, boolean firstWinRewarded) {
        jdbcTemplate.update("UPDATE user_results SET xp_delta = ?, first_win_rewarded = ? WHERE id = ?",
                xpDelta, firstWinRewarded, resultId);
    }
}
