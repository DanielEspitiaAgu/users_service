package co.edu.uptc.users_service.config;

import co.edu.uptc.users_service.entity.Achievement;
import co.edu.uptc.users_service.repository.AchievementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final AchievementRepository achievementRepository;

    @Override
    public void run(String... args) {
        createAchievementIfNotExists("FIRST_WIN", "Primera victoria",
                "Gana tu primer caso", "🏆", "WINS>=1");
        createAchievementIfNotExists("WINS_3", "En racha",
                "Gana 3 casos", "🥉", "WINS>=3");
        createAchievementIfNotExists("WINS_5", "Investigador habitual",
                "Gana 5 casos", "🔎", "WINS>=5");
        createAchievementIfNotExists("WINS_10", "Veterano",
                "Gana 10 casos", "🎖️", "WINS>=10");
        createAchievementIfNotExists("WINS_25", "Leyenda detectivesca",
                "Gana 25 casos", "🕵️", "WINS>=25");
        createAchievementIfNotExists("XP_100", "Aprendiz",
                "Acumula 100 XP", "⭐", "XP>=100");
        createAchievementIfNotExists("XP_250", "Agudo observador",
                "Acumula 250 XP", "💡", "XP>=250");
        createAchievementIfNotExists("XP_500", "Mente brillante",
                "Acumula 500 XP", "🧠", "XP>=500");
        createAchievementIfNotExists("XP_1000", "Experto",
                "Acumula 1000 XP", "🌟", "XP>=1000");
        createAchievementIfNotExists("STREAK_3", "Tres días de casos",
                "Mantén una racha de 3 días", "📅", "STREAK>=3");
        createAchievementIfNotExists("STREAK_7", "Semana de detective",
                "Mantén una racha de 7 días", "🗓️", "STREAK>=7");
    }

    private void createAchievementIfNotExists(String code, String name, String desc, String icon, String req) {
        achievementRepository.findByCode(code).orElseGet(() -> achievementRepository.save(Achievement.builder()
                .code(code).name(name).description(desc).icon(icon).requirement(req)
                .build()));
    }
}
