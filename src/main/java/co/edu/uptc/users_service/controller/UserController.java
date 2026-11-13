package co.edu.uptc.users_service.controller;

import co.edu.uptc.users_service.dto.*;
import co.edu.uptc.users_service.application.port.in.GetOrCreateProfileUseCase;
import co.edu.uptc.users_service.application.port.in.UpdateProfileUseCase;
import co.edu.uptc.users_service.domain.model.ProfileUpdate;
import co.edu.uptc.users_service.mapper.UserMapper;
import co.edu.uptc.users_service.service.StatsService;
import co.edu.uptc.users_service.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "Usuarios", description = "Operaciones sobre el perfil, estadísticas y logros del usuario autenticado")
public class UserController {

    private final UserService userService;
    private final GetOrCreateProfileUseCase getOrCreateProfile;
    private final UpdateProfileUseCase updateProfile;
    private final UserMapper userMapper;
    private final StatsService statsService;

    @GetMapping("/me")
    @Operation(summary = "Obtener o crear el perfil del jugador autenticado por Keycloak")
    public ResponseEntity<UserDTO> me(Authentication auth) {
        return ResponseEntity.ok(userMapper.toDTO(getOrCreateProfile.getOrCreate(auth.getName()), getRoles(auth)));
    }

    @PutMapping("/me")
    @Operation(summary = "Actualizar nombre y avatar del perfil del jugador autenticado")
    public ResponseEntity<UserDTO> updateProfile(Authentication auth,
            @Valid @RequestBody UpdateProfileDTO dto) {
        return ResponseEntity.ok(userMapper.toDTO(updateProfile.update(auth.getName(), new ProfileUpdate(dto.name(), dto.avatar())), getRoles(auth)));
    }

    @PatchMapping("/me/language")
    @Operation(summary = "Cambiar idioma del usuario (es/en)",
            description = "Actualiza el idioma preferido del usuario autenticado.")
    public ResponseEntity<UserDTO> updateLanguage(Authentication auth,
            @Valid @RequestBody LanguageDTO dto) {
        return ResponseEntity.ok(userService.updateLanguage(auth.getName(), getRoles(auth), dto));
    }

    @PutMapping("/me/tutorial")
    @Operation(summary = "Marcar tutorial como visto")
    public ResponseEntity<UserDTO> updateTutorial(Authentication auth,
            @Valid @RequestBody TutorialDTO dto) {
        return ResponseEntity.ok(userService.updateTutorial(auth.getName(), getRoles(auth), dto));
    }

    @GetMapping("/me/stats")
    @Operation(summary = "Ver estadísticas del usuario")
    public ResponseEntity<StatsDTO> stats(Authentication auth) {
        return ResponseEntity.ok(statsService.getStats(auth.getName()));
    }

    @GetMapping("/me/achievements")
    @Operation(summary = "Ver logros desbloqueados")
    public ResponseEntity<List<AchievementDTO>> achievements(Authentication auth) {
        return ResponseEntity.ok(statsService.getAchievements(auth.getName()));
    }

    @PostMapping("/{id}/results")
    @Operation(summary = "Registrar resultado de partida para un jugador Keycloak (USO INTERNO)",
            description = "Solo para otros servicios internos: exige X-Internal-Token. matchId es idempotente; "
                    + "CLASSIC requiere dificultad y calcula XP aquí. TIMED/VERSUS conservan xpDelta enviado "
                    + "por el servicio interno. Nunca es un endpoint para Godot.")
    @Parameter(name = "X-Internal-Token", in = ParameterIn.HEADER, required = true)
    public ResponseEntity<ResultResponseDTO> registerResult(@PathVariable String id,
            @Valid @RequestBody ResultDTO dto) {
        return ResponseEntity.ok(statsService.registerResult(id, dto));
    }

    private Set<String> getRoles(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.equals("ROLE_USER") || authority.equals("ROLE_ADMIN"))
                .map(authority -> authority.substring("ROLE_".length()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
