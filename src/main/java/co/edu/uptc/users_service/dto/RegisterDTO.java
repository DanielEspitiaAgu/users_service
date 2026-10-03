package co.edu.uptc.users_service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterDTO(

        @NotBlank(message = "El username es obligatorio") @Size(min = 3, max = 50) String username,

        @NotBlank(message = "El email es obligatorio") @Email(message = "El email no es válido") String email,

        @NotBlank(message = "La contraseña es obligatoria") @Size(min = 6, max = 100) String password,

        String avatar) {
}