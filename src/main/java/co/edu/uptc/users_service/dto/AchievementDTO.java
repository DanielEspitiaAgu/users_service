package co.edu.uptc.users_service.dto;

import java.time.LocalDateTime;

public record AchievementDTO(
        Long id,
        String code,
        String name,
        String description,
        String icon,
        LocalDateTime unlockedAt) {
}