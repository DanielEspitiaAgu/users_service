package co.edu.uptc.users_service.dto;

import java.util.List;

public record StatsDTO(
        int solvedCases,
        int totalTime,
        int streak,
        List<BestTimeDTO> bestTimes,
        int experience,
        int uniqueCasesSolved) {
    public record BestTimeDTO(String caseId, String mode, int bestTimeSeconds) {
    }
}
