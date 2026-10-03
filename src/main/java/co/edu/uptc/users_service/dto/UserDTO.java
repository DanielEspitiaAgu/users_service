package co.edu.uptc.users_service.dto;

import java.time.LocalDateTime;
import java.util.Set;

public record UserDTO(
        Long id,
        String username,
        String email,
        String name,
        String avatar,
        String language,
        Boolean tutorialCompleted,
        Integer experience,
        Integer streak,
        Set<String> roles,
        LocalDateTime createdAt) {
}