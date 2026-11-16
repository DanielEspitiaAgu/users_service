package co.edu.uptc.users_service.application.usecase;

import co.edu.uptc.users_service.application.port.in.StatsUseCase;
import co.edu.uptc.users_service.application.port.out.StatsStore;
import co.edu.uptc.users_service.domain.model.AchievementDefinition;
import co.edu.uptc.users_service.domain.model.GameResultSubmission;
import co.edu.uptc.users_service.domain.model.StatsPlayer;
import co.edu.uptc.users_service.domain.model.StoredResult;
import co.edu.uptc.users_service.domain.model.UnlockedAchievement;
import co.edu.uptc.users_service.domain.service.ProgressionPolicy;
import co.edu.uptc.users_service.dto.AchievementDTO;
import co.edu.uptc.users_service.dto.ProgressionDTO;
import co.edu.uptc.users_service.dto.ResultDTO;
import co.edu.uptc.users_service.dto.ResultResponseDTO;
import co.edu.uptc.users_service.dto.StatsDTO;
import co.edu.uptc.users_service.exception.ResultConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsUseCaseImpl implements StatsUseCase {
    private final StatsStore statsStore;
    private final ProgressionPolicy progressionPolicy;

    @Override
    @Transactional(readOnly = true)
    public StatsDTO getStats(String externalId) {
        StatsPlayer player = statsStore.getOrCreatePlayer(externalId);
        return buildStats(player);
    }

    @Override
    @Transactional(readOnly = true)
    public ProgressionDTO getProgression(String externalId) {
        int experience = statsStore.getOrCreatePlayer(externalId).experience();
        return new ProgressionDTO(
                experience,
                progressionPolicy.levelForExperience(experience),
                progressionPolicy.nextLevelExperience(experience));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AchievementDTO> getAchievements(String externalId) {
        StatsPlayer player = statsStore.getOrCreatePlayer(externalId);
        return statsStore.findUnlockedAchievements(player.id()).stream()
                .map(StatsUseCaseImpl::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ResultResponseDTO registerResult(String externalId, ResultDTO dto) {
        validateResultRequest(dto);
        StatsPlayer player = statsStore.getOrCreateAndLockPlayer(externalId);
        long userId = player.id();

        Optional<StoredResult> persisted =
                statsStore.findResultByUserIdAndMatchId(userId, dto.matchId());
        if (persisted.isPresent()) {
            StoredResult result = persisted.get();
            if (!matchesRequest(result, dto)) {
                throw new ResultConflictException();
            }
            return toResponse(result, buildStats(player), List.of());
        }

        int nextStreak = calculateNextStreak(userId, player.streak());
        boolean classic = "CLASSIC".equals(dto.mode());
        int initialXp = classic ? 0 : dto.xpDelta();
        Long resultId = statsStore.insertResultIfAbsent(userId, new GameResultSubmission(
                dto.matchId(), dto.caseId(), dto.mode(), dto.difficulty(),
                dto.won(), dto.timeSeconds(), initialXp));

        if (resultId == null) {
            StoredResult concurrentResult = statsStore.findResultByUserIdAndMatchId(userId, dto.matchId())
                    .orElseThrow(() -> new IllegalStateException(
                            "No se pudo insertar ni recuperar el resultado"));
            if (!matchesRequest(concurrentResult, dto)) {
                throw new ResultConflictException();
            }
            return toResponse(concurrentResult, buildStats(player), List.of());
        }

        StoredResult result = statsStore.findResultById(resultId)
                .orElseThrow(() -> new IllegalStateException("No se pudo leer el resultado insertado"));
        int appliedXp = initialXp;
        boolean firstWinRewarded = false;

        if (classic && Boolean.TRUE.equals(dto.won())) {
            int reward = classicReward(dto.difficulty());
            firstWinRewarded = statsStore.claimClassicFirstWin(userId, dto.caseId(), resultId, reward);
            appliedXp = firstWinRewarded ? reward : 0;
            statsStore.updateAppliedReward(resultId, appliedXp, firstWinRewarded);
            result = withReward(result, appliedXp, firstWinRewarded);
        }

        int experience = player.experience() + appliedXp;
        statsStore.updatePlayerProgress(userId, experience, nextStreak);
        player = new StatsPlayer(player.id(), experience, nextStreak);

        List<AchievementDTO> newAchievements = evaluateAchievements(player);
        return toResponse(result, buildStats(player), newAchievements);
    }

    private StatsDTO buildStats(StatsPlayer player) {
        List<StoredResult> results = statsStore.findResultsByUserId(player.id());
        int solvedCases = (int) results.stream().filter(StoredResult::won).count();
        long matchesPlayed = results.size();
        int totalTime = results.stream().mapToInt(StoredResult::timeSeconds).sum();

        Map<String, Integer> bestByKey = new HashMap<>();
        Map<String, String[]> keyInfo = new HashMap<>();
        for (StoredResult result : results) {
            if (!result.won()) {
                continue;
            }
            String key = result.caseId() + "|" + result.mode();
            keyInfo.putIfAbsent(key, new String[] { result.caseId(), result.mode() });
            bestByKey.merge(key, result.timeSeconds(), Math::min);
        }

        List<StatsDTO.BestTimeDTO> bestTimes = bestByKey.entrySet().stream()
                .map(entry -> new StatsDTO.BestTimeDTO(
                        keyInfo.get(entry.getKey())[0],
                        keyInfo.get(entry.getKey())[1],
                        entry.getValue()))
                .collect(Collectors.toList());

        return new StatsDTO(
                solvedCases,
                totalTime,
                player.streak(),
                bestTimes,
                player.experience(),
                Math.toIntExact(statsStore.countUniqueCasesSolvedByUserId(player.id())),
                matchesPlayed,
                solvedCases,
                progressionPolicy.levelForExperience(player.experience()),
                progressionPolicy.nextLevelExperience(player.experience()));
    }

    private int calculateNextStreak(long userId, int currentStreak) {
        LocalDate today = LocalDate.now();
        Optional<LocalDateTime> lastPlay = statsStore.findLastPlayDateByUserId(userId);
        if (lastPlay.isEmpty()) {
            return 1;
        }
        LocalDate lastPlayDate = lastPlay.get().toLocalDate();
        if (lastPlayDate.equals(today.minusDays(1))) {
            return currentStreak + 1;
        }
        if (lastPlayDate.isBefore(today.minusDays(1))) {
            return 1;
        }
        return currentStreak;
    }

    private static void validateResultRequest(ResultDTO dto) {
        if (dto == null || dto.matchId() == null || dto.matchId().isBlank()
                || dto.matchId().length() > 255 || dto.caseId() == null || dto.caseId().isBlank()
                || dto.caseId().length() > 255 || dto.won() == null || dto.timeSeconds() == null
                || dto.timeSeconds() < 0) {
            throw new IllegalArgumentException("Datos de resultado inválidos");
        }
        if (dto.mode() == null || !Set.of("CLASSIC", "TIMED", "VERSUS").contains(dto.mode())) {
            throw new IllegalArgumentException("Modo de partida inválido");
        }
        if ("CLASSIC".equals(dto.mode())) {
            if (dto.difficulty() == null || !Set.of("EASY", "MEDIUM", "HARD").contains(dto.difficulty())) {
                throw new IllegalArgumentException("La dificultad CLASSIC debe ser EASY, MEDIUM o HARD");
            }
            if (dto.xpDelta() != null) {
                throw new IllegalArgumentException("CLASSIC no acepta xpDelta; users_service calcula la recompensa");
            }
        } else if (dto.xpDelta() == null) {
            throw new IllegalArgumentException("TIMED y VERSUS requieren el xpDelta del servicio interno");
        }
    }

    private static int classicReward(String difficulty) {
        return switch (difficulty) {
            case "EASY" -> 50;
            case "MEDIUM" -> 100;
            case "HARD" -> 150;
            default -> throw new IllegalArgumentException("Dificultad CLASSIC inválida");
        };
    }

    private static boolean matchesRequest(StoredResult result, ResultDTO dto) {
        boolean sameTerminalFields = Objects.equals(result.caseId(), dto.caseId())
                && Objects.equals(result.mode(), dto.mode())
                && Objects.equals(result.won(), dto.won())
                && Objects.equals(result.timeSeconds(), dto.timeSeconds())
                && Objects.equals(result.difficulty(), dto.difficulty());
        return sameTerminalFields && ("CLASSIC".equals(dto.mode())
                || Objects.equals(result.xpDelta(), dto.xpDelta()));
    }

    private static ResultResponseDTO toResponse(
            StoredResult result, StatsDTO stats, List<AchievementDTO> newAchievements) {
        return new ResultResponseDTO(
                result.matchId(), result.caseId(), result.mode(),
                Boolean.TRUE.equals(result.won()), result.xpDelta(),
                Boolean.TRUE.equals(result.firstWinRewarded()), stats, newAchievements);
    }

    private List<AchievementDTO> evaluateAchievements(StatsPlayer player) {
        List<AchievementDTO> newAchievements = new ArrayList<>();
        long wins = statsStore.countWinsByUserId(player.id());

        for (AchievementDefinition achievement : statsStore.findAllAchievements()) {
            if (statsStore.hasAchievement(player.id(), achievement.id())) {
                continue;
            }
            if (meetsRequirement(achievement.requirement(), wins, player)) {
                newAchievements.add(toDto(statsStore.unlockAchievement(player.id(), achievement.id())));
            }
        }
        return newAchievements;
    }

    private boolean meetsRequirement(String requirement, long wins, StatsPlayer player) {
        if (requirement.startsWith("WINS>=")) {
            return wins >= Integer.parseInt(requirement.substring(6));
        }
        if (requirement.startsWith("XP>=")) {
            return player.experience() >= Integer.parseInt(requirement.substring(4));
        }
        if (requirement.startsWith("STREAK>=")) {
            return player.streak() >= Integer.parseInt(requirement.substring(8));
        }
        throw new IllegalStateException("Requisito de logro no reconocido: " + requirement);
    }

    private static StoredResult withReward(StoredResult result, int xpDelta, boolean rewarded) {
        return new StoredResult(result.id(), result.matchId(), result.caseId(), result.mode(),
                result.difficulty(), result.won(), result.timeSeconds(), xpDelta, rewarded);
    }

    private static AchievementDTO toDto(UnlockedAchievement achievement) {
        return new AchievementDTO(achievement.id(), achievement.code(), achievement.name(),
                achievement.description(), achievement.icon(), achievement.unlockedAt());
    }
}
