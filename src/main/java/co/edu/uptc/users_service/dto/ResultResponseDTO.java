package co.edu.uptc.users_service.dto;

import java.util.List;

public record ResultResponseDTO(
        StatsDTO stats,
        List<AchievementDTO> newAchievements) {
}