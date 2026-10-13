package co.edu.uptc.users_service.controller;

import co.edu.uptc.users_service.dto.*;
import co.edu.uptc.users_service.service.AuthService;
import co.edu.uptc.users_service.service.StatsService;
import co.edu.uptc.users_service.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "Usuarios", description = "Operaciones sobre el perfil, estadísticas y logros del usuario autenticado")
public class UserController {

    private final AuthService authService;
    private final UserService userService;
    private final StatsService statsService;

    @GetMapping("/me")
    @Operation(summary = "Obtener perfil del usuario autenticado")
    public ResponseEntity<UserDTO> me(Authentication auth) {
        return ResponseEntity.ok(authService.getCurrentUser(auth.getName()));
    }

    @PutMapping("/me")
    @Operation(summary = "Actualizar nombre y avatar del perfil")
    public ResponseEntity<UserDTO> updateProfile(Authentication auth,
            @Valid @RequestBody UpdateProfileDTO dto) {
        return ResponseEntity.ok(userService.updateProfile(auth.getName(), dto));
    }

    @PatchMapping("/me/language")
    @Operation(summary = "Marcar tutorial como visto")
    public ResponseEntity<UserDTO> updateLanguage(Authentication auth,
            @Valid @RequestBody LanguageDTO dto) {
        return ResponseEntity.ok(userService.updateLanguage(auth.getName(), dto));
    }

    @PutMapping("/me/tutorial")
    @Operation(summary = "Marcar tutorial como visto")
    public ResponseEntity<UserDTO> updateTutorial(Authentication auth,
            @Valid @RequestBody TutorialDTO dto) {
        return ResponseEntity.ok(userService.updateTutorial(auth.getName(), dto));
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
    @Operation(summary = "Registrar resultado de partida (uso interno)")
    public ResponseEntity<ResultResponseDTO> registerResult(@PathVariable Long id,
            @Valid @RequestBody ResultDTO dto) {
        return ResponseEntity.ok(statsService.registerResult(id, dto));
    }
}