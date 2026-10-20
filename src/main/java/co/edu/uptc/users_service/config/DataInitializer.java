package co.edu.uptc.users_service.config;

import co.edu.uptc.users_service.entity.Achievement;
import co.edu.uptc.users_service.entity.Role;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.repository.AchievementRepository;
import co.edu.uptc.users_service.repository.RoleRepository;
import co.edu.uptc.users_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final AchievementRepository achievementRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.username}")
    private String adminUsername;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        Role userRole = createRoleIfNotExists("ROLE_USER");
        Role adminRole = createRoleIfNotExists("ROLE_ADMIN");
        createRoleIfNotExists("ROLE_SERVICE");

        createAchievementIfNotExists("FIRST_WIN", "Primera victoria",
                "Gana tu primer caso", "🏆", "WINS>=1");
        createAchievementIfNotExists("WINS_10", "Veterano",
                "Gana 10 casos", "🎖️", "WINS>=10");
        createAchievementIfNotExists("XP_100", "Aprendiz",
                "Acumula 100 XP", "⭐", "XP>=100");
        createAchievementIfNotExists("XP_1000", "Experto",
                "Acumula 1000 XP", "🌟", "XP>=1000");

        createAdminIfNotExists(userRole, adminRole);
    }

    private Role createRoleIfNotExists(String name) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(Role.builder().name(name).build()));
    }

    private void createAchievementIfNotExists(String code, String name, String desc, String icon, String req) {
        achievementRepository.findByCode(code).orElseGet(() -> achievementRepository.save(Achievement.builder()
                .code(code).name(name).description(desc).icon(icon).requirement(req)
                .build()));
    }

    private void createAdminIfNotExists(Role userRole, Role adminRole) {
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("ADMIN_PASSWORD no está definida: no se crea el usuario administrador");
            return;
        }
        if (userRepository.existsByEmail(adminEmail) || userRepository.existsByUsername(adminUsername)) {
            return; // ya existe: no se modifica
        }
        User admin = User.builder()
                .username(adminUsername)
                .email(adminEmail)
                .password(passwordEncoder.encode(adminPassword))
                .name("Administrador")
                .tutorialCompleted(true)
                .roles(Set.of(userRole, adminRole))
                .build();
        userRepository.save(admin);
        log.info("Usuario administrador creado: {}", adminEmail);
    }
}