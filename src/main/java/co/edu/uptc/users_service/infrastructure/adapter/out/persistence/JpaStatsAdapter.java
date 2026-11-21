package co.edu.uptc.users_service.infrastructure.adapter.out.persistence;

import co.edu.uptc.users_service.application.port.out.StatsStore;
import co.edu.uptc.users_service.domain.model.AchievementDefinition;
import co.edu.uptc.users_service.domain.model.GameResultSubmission;
import co.edu.uptc.users_service.domain.model.StatsPlayer;
import co.edu.uptc.users_service.domain.model.StoredResult;
import co.edu.uptc.users_service.domain.model.UnlockedAchievement;
import co.edu.uptc.users_service.entity.Achievement;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.entity.UserAchievement;
import co.edu.uptc.users_service.entity.UserResult;
import co.edu.uptc.users_service.repository.AchievementRepository;
import co.edu.uptc.users_service.repository.ResultWriteRepository;
import co.edu.uptc.users_service.repository.UserAchievementRepository;
import co.edu.uptc.users_service.repository.UserRepository;
import co.edu.uptc.users_service.repository.UserResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JpaStatsAdapter implements StatsStore {
    private final JpaUserProfileAccess profileAccess;
    private final UserRepository userRepository;
    private final UserResultRepository userResultRepository;
    private final ResultWriteRepository resultWriteRepository;
    private final AchievementRepository achievementRepository;
    private final UserAchievementRepository userAchievementRepository;

    @Override
    public StatsPlayer getOrCreatePlayer(String externalId) {
        return toStatsPlayer(profileAccess.getOrCreate(externalId));
    }

    @Override
    @Transactional
    public StatsPlayer getOrCreateAndLockPlayer(String externalId) {
        profileAccess.getOrCreate(externalId);
        User user = userRepository.findByExternalIdForUpdate(externalId)
                .orElseThrow(() -> new IllegalStateException("No fue posible bloquear el perfil"));
        return toStatsPlayer(user);
    }

    @Override
    public List<StoredResult> findResultsByUserId(long userId) {
        return userResultRepository.findByUserId(userId).stream()
                .map(JpaStatsAdapter::toStoredResult)
                .toList();
    }

    @Override
    public Optional<StoredResult> findResultByUserIdAndMatchId(long userId, String matchId) {
        return userResultRepository.findByUserIdAndMatchId(userId, matchId)
                .map(JpaStatsAdapter::toStoredResult);
    }

    @Override
    public Optional<StoredResult> findResultById(long resultId) {
        return userResultRepository.findById(resultId).map(JpaStatsAdapter::toStoredResult);
    }

    @Override
    public long countUniqueCasesSolvedByUserId(long userId) {
        return userResultRepository.countUniqueCasesSolvedByUserId(userId);
    }

    @Override
    public List<String> findSolvedCaseIdsByUserId(long userId) {
        return userResultRepository.findSolvedCaseIdsByUserId(userId);
    }

    @Override
    public long countWinsByUserId(long userId) {
        return userResultRepository.countWinsByUserId(userId);
    }

    @Override
    public Optional<LocalDateTime> findLastPlayDateByUserId(long userId) {
        return userResultRepository.findLastPlayDateByUserId(userId);
    }

    @Override
    @Transactional
    public Long insertResultIfAbsent(long userId, GameResultSubmission result) {
        return resultWriteRepository.insertIfAbsent(userId, result.matchId(), result.caseId(),
                result.mode(), result.difficulty(), result.won(), result.timeSeconds(), result.xpDelta());
    }

    @Override
    @Transactional
    public boolean claimClassicFirstWin(long userId, String caseId, long resultId, int xpAwarded) {
        return resultWriteRepository.claimClassicFirstWin(userId, caseId, resultId, xpAwarded);
    }

    @Override
    @Transactional
    public void updateAppliedReward(long resultId, int xpDelta, boolean firstWinRewarded) {
        resultWriteRepository.updateAppliedReward(resultId, xpDelta, firstWinRewarded);
    }

    @Override
    @Transactional
    public void updatePlayerProgress(long userId, int experience, int streak) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("No fue posible actualizar el perfil"));
        user.setExperience(experience);
        user.setStreak(streak);
        userRepository.save(user);
    }

    @Override
    public List<AchievementDefinition> findAllAchievements() {
        return achievementRepository.findAll().stream()
                .map(JpaStatsAdapter::toDefinition)
                .toList();
    }

    @Override
    public boolean hasAchievement(long userId, long achievementId) {
        return userAchievementRepository.existsByUserIdAndAchievementId(userId, achievementId);
    }

    @Override
    @Transactional
    public UnlockedAchievement unlockAchievement(long userId, long achievementId) {
        User user = userRepository.getReferenceById(userId);
        Achievement achievement = achievementRepository.findById(achievementId)
                .orElseThrow(() -> new IllegalStateException("No se encontró el logro configurado"));
        UserAchievement unlocked = userAchievementRepository.save(UserAchievement.builder()
                .user(user)
                .achievement(achievement)
                .build());
        return toUnlockedAchievement(unlocked);
    }

    @Override
    public List<UnlockedAchievement> findUnlockedAchievements(long userId) {
        return userAchievementRepository.findByUserId(userId).stream()
                .map(JpaStatsAdapter::toUnlockedAchievement)
                .toList();
    }

    private static StatsPlayer toStatsPlayer(User user) {
        return new StatsPlayer(user.getId(), user.getExperience(), user.getStreak());
    }

    private static StoredResult toStoredResult(UserResult result) {
        return new StoredResult(result.getId(), result.getMatchId(), result.getCaseId(),
                result.getMode(), result.getDifficulty(), result.getWon(), result.getTimeSeconds(),
                result.getXpDelta(), result.getFirstWinRewarded());
    }

    private static AchievementDefinition toDefinition(Achievement achievement) {
        return new AchievementDefinition(achievement.getId(), achievement.getCode(), achievement.getName(),
                achievement.getDescription(), achievement.getIcon(), achievement.getRequirement());
    }

    private static UnlockedAchievement toUnlockedAchievement(UserAchievement unlocked) {
        Achievement achievement = unlocked.getAchievement();
        return new UnlockedAchievement(achievement.getId(), achievement.getCode(), achievement.getName(),
                achievement.getDescription(), achievement.getIcon(), unlocked.getUnlockedAt());
    }
}
