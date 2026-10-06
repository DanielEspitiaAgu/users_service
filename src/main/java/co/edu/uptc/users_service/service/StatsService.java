package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.dto.*;
import co.edu.uptc.users_service.entity.Achievement;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.entity.UserAchievement;
import co.edu.uptc.users_service.entity.UserResult;
import co.edu.uptc.users_service.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final UserRepository userRepository;
    private final UserResultRepository userResultRepository;
    private final AchievementRepository achievementRepository;
    private final UserAchievementRepository userAchievementRepository;

    @Transactional(readOnly = true)
    public StatsDTO getStats(String email) {
        User user = findUserByEmail(email);
        return buildStats(user);
    }

    @Transactional(readOnly = true)
    public List<AchievementDTO> getAchievements(String email) {
        User user = findUserByEmail(email);
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
    public ResultResponseDTO registerResult(Long userId, ResultDTO dto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        UserResult result = UserResult.builder()
                .user(user)
                .caseId(dto.caseId())
                .mode(dto.mode())
                .won(dto.won())
                .timeSeconds(dto.timeSeconds())
                .xpDelta(dto.xpDelta())
                .build();
        userResultRepository.save(result);

        // Actualizar XP del usuario
        user.setExperience(user.getExperience() + dto.xpDelta());
        userRepository.save(user);

        // Evaluar logros nuevos
        List<AchievementDTO> nuevos = evaluateAchievements(user);

        return new ResultResponseDTO(buildStats(user), nuevos);
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
                user.getExperience());
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

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
    }
}
