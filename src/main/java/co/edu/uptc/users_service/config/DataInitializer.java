package co.edu.uptc.users_service.config;

import co.edu.uptc.users_service.entity.Achievement;
import co.edu.uptc.users_service.entity.Role;
import co.edu.uptc.users_service.repository.AchievementRepository;
import co.edu.uptc.users_service.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final AchievementRepository achievementRepository;

    @Override
    public void run(String... args) {
        createRoleIfNotExists("ROLE_USER");
        createRoleIfNotExists("ROLE_ADMIN");

        createAchievementIfNotExists("FIRST_WIN", "Primera victoria",
                "Gana tu primer caso", "🏆", "WINS>=1");
        createAchievementIfNotExists("WINS_10", "Veterano",
                "Gana 10 casos", "🎖️", "WINS>=10");
        createAchievementIfNotExists("XP_100", "Aprendiz",
                "Acumula 100 XP", "⭐", "XP>=100");
        createAchievementIfNotExists("XP_1000", "Experto",
                "Acumula 1000 XP", "🌟", "XP>=1000");
    }

    private void createRoleIfNotExists(String name) {
        roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder().name(name).build()));
    }

    private void createAchievementIfNotExists(String code, String name, String desc, String icon, String req) {
        achievementRepository.findByCode(code).orElseGet(() -> achievementRepository.save(Achievement.builder()
                .code(code).name(name).description(desc).icon(icon).requirement(req)
                .build()));
    }
}