package co.edu.uptc.users_service.dto;

import java.util.List;

public record ResultResponseDTO(
        String matchId,
        String caseId,
        String mode,
        boolean won,
        int xpDelta,
        boolean firstWinRewarded,
        StatsDTO stats,
        List<AchievementDTO> newAchievements) {
}
