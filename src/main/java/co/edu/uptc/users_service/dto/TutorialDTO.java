package co.edu.uptc.users_service.dto;

import jakarta.validation.constraints.NotNull;

public record TutorialDTO(
        @NotNull(message = "El campo 'completed' es obligatorio") Boolean completed) {
}