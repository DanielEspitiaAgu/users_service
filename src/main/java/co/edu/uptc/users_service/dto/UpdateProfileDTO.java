package co.edu.uptc.users_service.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileDTO(
        @Size(max = 100, message = "El nombre no puede superar 100 caracteres") String name,

        @Size(max = 255) String avatar) {
}