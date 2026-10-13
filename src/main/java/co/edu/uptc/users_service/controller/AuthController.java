package co.edu.uptc.users_service.controller;

import co.edu.uptc.users_service.dto.*;
import co.edu.uptc.users_service.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticación", description = "Registro y login de usuarios")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Registrar un nuevo usuario", description = "Crea un usuario con rol ROLE_USER. Devuelve el UserDTO.")
    @ApiResponse(responseCode = "201", description = "Usuario creado exitosamente")
    @ApiResponse(responseCode = "400", description = "Datos inválidos o usuario/email ya existe")
    public ResponseEntity<UserDTO> register(@Valid @RequestBody RegisterDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(dto));
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión", description = "Autentica un usuario y devuelve un token JWT.")
    @ApiResponse(responseCode = "200", description = "Login exitoso")
    @ApiResponse(responseCode = "401", description = "Credenciales inválidas")
    public ResponseEntity<AuthResponseDTO> login(@Valid @RequestBody LoginDTO dto) {
        return ResponseEntity.ok(authService.login(dto));
    }
}