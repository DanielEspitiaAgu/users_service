package co.edu.uptc.users_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "case_id", nullable = false)
    private String caseId;

    @Column(nullable = false, length = 20)
    private String mode;

    @Column(nullable = false)
    private Boolean won;

    @Column(name = "time_seconds", nullable = false)
    private Integer timeSeconds;

    @Column(name = "xp_delta", nullable = false)
    private Integer xpDelta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}