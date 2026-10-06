package co.edu.uptc.users_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ResultDTO(
        @NotBlank String caseId,
        @NotBlank String mode,
        @NotNull Boolean won,
        @NotNull @PositiveOrZero Integer timeSeconds,
        @NotNull Integer xpDelta) {
}