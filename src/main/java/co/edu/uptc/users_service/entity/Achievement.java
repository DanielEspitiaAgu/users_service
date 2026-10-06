package co.edu.uptc.users_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "achievements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Achievement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code; // ej: "FIRST_WIN", "STREAK_7"

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(length = 255)
    private String icon;

    // Requisito simple: "WINS>=1", "STREAK>=7", "XP>=1000"
    @Column(name = "requirement", nullable = false, length = 50)
    private String requirement;
}