package co.edu.uptc.users_service.domain.service;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProgressionPolicy {

    private static final List<Integer> LEVEL_THRESHOLDS = List.of(
            0, 50, 100, 150, 200, 300, 425, 575, 750, 950,
            1200, 1500, 1850, 2250, 2700, 3200, 3750, 4350, 5000, 5700);

    public int levelForExperience(int experience) {
        int level = 1;
        for (int index = 1; index < LEVEL_THRESHOLDS.size(); index++) {
            if (experience < LEVEL_THRESHOLDS.get(index)) {
                break;
            }
            level = index + 1;
        }
        return level;
    }

    public int nextLevelExperience(int experience) {
        int level = levelForExperience(experience);
        return level < LEVEL_THRESHOLDS.size() ? LEVEL_THRESHOLDS.get(level) : experience;
    }

    public int requiredLevel(String difficulty) {
        return switch (difficulty) {
            case "EASY" -> 1;
            case "MEDIUM" -> 5;
            case "HARD" -> 10;
            default -> throw new IllegalArgumentException("Dificultad no reconocida");
        };
    }
}
