package co.edu.uptc.users_service.domain.model;

public record StoredResult(
        Long id,
        String matchId,
        String caseId,
        String mode,
        String difficulty,
        Boolean won,
        Integer timeSeconds,
        Integer xpDelta,
        Boolean firstWinRewarded) {
}
