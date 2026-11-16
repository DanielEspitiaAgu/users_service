package co.edu.uptc.users_service.domain.model;

public record GameResultSubmission(
        String matchId,
        String caseId,
        String mode,
        String difficulty,
        boolean won,
        int timeSeconds,
        int xpDelta) {
}
