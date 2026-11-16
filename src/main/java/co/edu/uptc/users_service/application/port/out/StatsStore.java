package co.edu.uptc.users_service.application.port.out;

import co.edu.uptc.users_service.domain.model.AchievementDefinition;
import co.edu.uptc.users_service.domain.model.GameResultSubmission;
import co.edu.uptc.users_service.domain.model.StatsPlayer;
import co.edu.uptc.users_service.domain.model.StoredResult;
import co.edu.uptc.users_service.domain.model.UnlockedAchievement;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StatsStore {
    StatsPlayer getOrCreatePlayer(String externalId);

    StatsPlayer getOrCreateAndLockPlayer(String externalId);

    List<StoredResult> findResultsByUserId(long userId);

    Optional<StoredResult> findResultByUserIdAndMatchId(long userId, String matchId);

    Optional<StoredResult> findResultById(long resultId);

    long countUniqueCasesSolvedByUserId(long userId);

    long countWinsByUserId(long userId);

    Optional<LocalDateTime> findLastPlayDateByUserId(long userId);

    Long insertResultIfAbsent(long userId, GameResultSubmission result);

    boolean claimClassicFirstWin(long userId, String caseId, long resultId, int xpAwarded);

    void updateAppliedReward(long resultId, int xpDelta, boolean firstWinRewarded);

    void updatePlayerProgress(long userId, int experience, int streak);

    List<AchievementDefinition> findAllAchievements();

    boolean hasAchievement(long userId, long achievementId);

    UnlockedAchievement unlockAchievement(long userId, long achievementId);

    List<UnlockedAchievement> findUnlockedAchievements(long userId);
}
