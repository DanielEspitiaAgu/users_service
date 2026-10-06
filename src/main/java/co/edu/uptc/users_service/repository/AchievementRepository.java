package co.edu.uptc.users_service.repository;

import co.edu.uptc.users_service.entity.Achievement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AchievementRepository extends JpaRepository<Achievement, Long> {
    Optional<Achievement> findByCode(String code);
}