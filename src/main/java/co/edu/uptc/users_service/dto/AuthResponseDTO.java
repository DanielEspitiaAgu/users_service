package co.edu.uptc.users_service.dto;

public record AuthResponseDTO(
    String accessToken,
    String tokenType,
    long expiresIn) {
}