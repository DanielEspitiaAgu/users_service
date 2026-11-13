package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.dto.*;
import co.edu.uptc.users_service.entity.Achievement;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.entity.UserAchievement;
import co.edu.uptc.users_service.entity.UserResult;
import co.edu.uptc.users_service.exception.ResultConflictException;
import co.edu.uptc.users_service.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final UserRepository userRepository;
    private final UserProfileService profileService;
    private final UserResultRepository userResultRepository;
    private final ResultWriteRepository resultWriteRepository;
    private final AchievementRepository achievementRepository;
    private final UserAchievementRepository userAchievementRepository;

    @Transactional(readOnly = true)
    public StatsDTO getStats(String externalId) {
        User user = profileService.getOrCreate(externalId);
        return buildStats(user);
    }

    @Transactional(readOnly = true)
    public List<AchievementDTO> getAchievements(String externalId) {
        User user = profileService.getOrCreate(externalId);
        return userAchievementRepository.findByUserId(user.getId()).stream()
                .map(ua -> new AchievementDTO(
                        ua.getAchievement().getId(),
                        ua.getAchievement().getCode(),
                        ua.getAchievement().getName(),
                        ua.getAchievement().getDescription(),
                        ua.getAchievement().getIcon(),
                        ua.getUnlockedAt()))
                .collect(Collectors.toList());
    }

    @Transactional
    public ResultResponseDTO registerResult(String externalId, ResultDTO dto) {
        validateResultRequest(dto);
        User user = profileService.getOrCreate(externalId);
        user = userRepository.findByExternalIdForUpdate(externalId)
                .orElseThrow(() -> new IllegalStateException("No fue posible bloquear el perfil"));
        Long userId = user.getId();

        Optional<UserResult> persisted = userResultRepository.findByUserIdAndMatchId(userId, dto.matchId());
        if (persisted.isPresent()) {
            UserResult result = persisted.get();
            if (!matchesRequest(result, dto)) {
                throw new ResultConflictException();
            }
            return toResponse(result, buildStats(user), List.of());
        }

        int nextStreak = calculateNextStreak(userId, user.getStreak());
        boolean classic = "CLASSIC".equals(dto.mode());
        int initialXp = classic ? 0 : dto.xpDelta();
        Long resultId = resultWriteRepository.insertIfAbsent(
                userId, dto.matchId(), dto.caseId(), dto.mode(), dto.difficulty(),
                dto.won(), dto.timeSeconds(), initialXp);

        if (resultId == null) {
            UserResult concurrentResult = userResultRepository
                    .findByUserIdAndMatchId(userId, dto.matchId())
                    .orElseThrow(() -> new IllegalStateException("No se pudo insertar ni recuperar el resultado"));
            if (!matchesRequest(concurrentResult, dto)) {
                throw new ResultConflictException();
            }
            return toResponse(concurrentResult, buildStats(user), List.of());
        }

        UserResult result = userResultRepository.findById(resultId)
                .orElseThrow(() -> new IllegalStateException("No se pudo leer el resultado insertado"));
        int appliedXp = initialXp;
        boolean firstWinRewarded = false;

        if (classic && Boolean.TRUE.equals(dto.won())) {
            int reward = classicReward(dto.difficulty());
            firstWinRewarded = resultWriteRepository.claimClassicFirstWin(
                    userId, dto.caseId(), resultId, reward);
            appliedXp = firstWinRewarded ? reward : 0;
            resultWriteRepository.updateAppliedReward(resultId, appliedXp, firstWinRewarded);
            result.setXpDelta(appliedXp);
            result.setFirstWinRewarded(firstWinRewarded);
        }

        user.setStreak(nextStreak);
        if (appliedXp != 0) {
            user.setExperience(user.getExperience() + appliedXp);
        }
        userRepository.save(user);

        List<AchievementDTO> nuevos = evaluateAchievements(user);
        return toResponse(result, buildStats(user), nuevos);
    }

    private StatsDTO buildStats(User user) {
        List<UserResult> results = userResultRepository.findByUserId(user.getId());

        int solvedCases = (int) results.stream().filter(UserResult::getWon).count();
        int totalTime = results.stream().mapToInt(UserResult::getTimeSeconds).sum();

        // Best times: mínimo tiempo por (caseId, mode) solo de partidas ganadas
        Map<String, Integer> bestByKey = new HashMap<>();
        Map<String, String[]> keyInfo = new HashMap<>();

        for (UserResult r : results) {
            if (!r.getWon())
                continue;
            String key = r.getCaseId() + "|" + r.getMode();
            keyInfo.putIfAbsent(key, new String[] { r.getCaseId(), r.getMode() });
            bestByKey.merge(key, r.getTimeSeconds(), Math::min);
        }

        List<StatsDTO.BestTimeDTO> bestTimes = bestByKey.entrySet().stream()
                .map(e -> new StatsDTO.BestTimeDTO(
                        keyInfo.get(e.getKey())[0],
                        keyInfo.get(e.getKey())[1],
                        e.getValue()))
                .collect(Collectors.toList());

        return new StatsDTO(
                solvedCases,
                totalTime,
                user.getStreak(),
                bestTimes,
                user.getExperience(),
                Math.toIntExact(userResultRepository.countUniqueCasesSolvedByUserId(user.getId())));
    }

    private int calculateNextStreak(Long userId, int currentStreak) {
        LocalDate today = LocalDate.now();
        Optional<LocalDateTime> lastPlay = userResultRepository.findLastPlayDateByUserId(userId);
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

    private static boolean matchesRequest(UserResult result, ResultDTO dto) {
        boolean sameTerminalFields = Objects.equals(result.getCaseId(), dto.caseId())
                && Objects.equals(result.getMode(), dto.mode())
                && Objects.equals(result.getWon(), dto.won())
                && Objects.equals(result.getTimeSeconds(), dto.timeSeconds())
                && Objects.equals(result.getDifficulty(), dto.difficulty());
        return sameTerminalFields && ("CLASSIC".equals(dto.mode())
                || Objects.equals(result.getXpDelta(), dto.xpDelta()));
    }

    private static ResultResponseDTO toResponse(UserResult result, StatsDTO stats,
            List<AchievementDTO> newAchievements) {
        return new ResultResponseDTO(
                result.getMatchId(), result.getCaseId(), result.getMode(),
                Boolean.TRUE.equals(result.getWon()), result.getXpDelta(),
                Boolean.TRUE.equals(result.getFirstWinRewarded()), stats, newAchievements);
    }

    private List<AchievementDTO> evaluateAchievements(User user) {
        List<Achievement> todos = achievementRepository.findAll();
        List<AchievementDTO> nuevos = new ArrayList<>();

        long wins = userResultRepository.countWinsByUserId(user.getId());

        for (Achievement a : todos) {
            if (userAchievementRepository.existsByUserIdAndAchievementId(user.getId(), a.getId())) {
                continue; // ya lo tiene
            }

            if (cumpleRequisito(a.getRequirement(), wins, user)) {
                UserAchievement ua = UserAchievement.builder()
                        .user(user)
                        .achievement(a)
                        .build();
                userAchievementRepository.save(ua);

                nuevos.add(new AchievementDTO(
                        a.getId(), a.getCode(), a.getName(),
                        a.getDescription(), a.getIcon(), ua.getUnlockedAt()));
            }
        }
        return nuevos;
    }

    private boolean cumpleRequisito(String requirement, long wins, User user) {
        try {
            if (requirement.startsWith("WINS>=")) {
                return wins >= Integer.parseInt(requirement.substring(6));
            }
            if (requirement.startsWith("XP>=")) {
                return user.getExperience() >= Integer.parseInt(requirement.substring(4));
            }
            if (requirement.startsWith("STREAK>=")) {
                return user.getStreak() >= Integer.parseInt(requirement.substring(8));
            }
        } catch (Exception ignored) {
        }
        return false;
    }

}
