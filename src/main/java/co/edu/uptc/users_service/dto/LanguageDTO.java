package co.edu.uptc.users_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LanguageDTO(
    @NotBlank @Pattern(regexp = "es|en", message = "El idioma debe ser 'es' o 'en'") String language) {
}