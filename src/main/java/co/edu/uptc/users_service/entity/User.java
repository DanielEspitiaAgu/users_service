package co.edu.uptc.users_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true, unique = true, length = 50)
    private String username;

    @Column(nullable = true, unique = true, length = 100)
    private String email;

    @Column(name = "external_id", nullable = false, unique = true, length = 64)
    private String externalId;

    @Column(length = 100)
    private String name;

    @Column(length = 255)
    private String avatar;

    @Column(nullable = false, length = 5)
    @Builder.Default
    private String language = "es";

    @Column(name = "tutorial_completed", nullable = false)
    @Builder.Default
    private Boolean tutorialCompleted = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer experience = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer streak = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
