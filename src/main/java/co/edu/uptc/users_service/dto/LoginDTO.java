package co.edu.uptc.users_service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginDTO(
    @NotBlank @Email String email,
    @NotBlank String password) {
}