package co.edu.uptc.users_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ResultDTO(
        @NotBlank @Size(max = 255) String matchId,
        @NotBlank @Size(max = 255) String caseId,
        @NotBlank @Pattern(regexp = "CLASSIC|TIMED|VERSUS") String mode,
        @NotNull Boolean won,
        @NotNull @PositiveOrZero Integer timeSeconds,
        @Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty,
        Integer xpDelta) {
}
