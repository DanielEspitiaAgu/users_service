package co.edu.uptc.users_service.repository;

import co.edu.uptc.users_service.entity.UserResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserResultRepository extends JpaRepository<UserResult, Long> {

    @Query("SELECT r FROM UserResult r WHERE r.user.id = :userId")
    List<UserResult> findByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(r) FROM UserResult r WHERE r.user.id = :userId AND r.won = true")
    long countWinsByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(r.timeSeconds), 0) FROM UserResult r WHERE r.user.id = :userId")
    long sumTimeByUserId(@Param("userId") Long userId);

    @Query("SELECT MAX(r.createdAt) FROM UserResult r WHERE r.user.id = :userId")
    Optional<LocalDateTime> findLastPlayDateByUserId(@Param("userId") Long userId);
}