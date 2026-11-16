package co.edu.uptc.users_service.domain.model;

import java.time.LocalDateTime;

public record UnlockedAchievement(
        Long id,
        String code,
        String name,
        String description,
        String icon,
        LocalDateTime unlockedAt) {
}
