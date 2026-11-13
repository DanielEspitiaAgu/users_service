package co.edu.uptc.users_service.domain.model;

import java.time.LocalDateTime;

/** Framework-free profile data used by the profile application flow. */
public record PlayerProfile(
        String externalId,
        String username,
        String email,
        String name,
        String avatar,
        String language,
        Boolean tutorialCompleted,
        Integer experience,
        Integer streak,
        LocalDateTime createdAt) {
}


