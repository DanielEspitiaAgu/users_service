package co.edu.uptc.users_service.domain.model;

public record AchievementDefinition(
        Long id,
        String code,
        String name,
        String description,
        String icon,
        String requirement) {
}
