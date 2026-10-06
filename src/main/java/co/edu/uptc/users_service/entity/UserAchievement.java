package co.edu.uptc.users_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_achievements", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "achievement_id" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAchievement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "achievement_id", nullable = false)
    private Achievement achievement;

    @Column(name = "unlocked_at", nullable = false)
    private LocalDateTime unlockedAt;

    @PrePersist
    protected void onCreate() {
        this.unlockedAt = LocalDateTime.now();
    }
}