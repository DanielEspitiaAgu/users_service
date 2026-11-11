package co.edu.uptc.users_service.messaging;

public record PingEvent(
		String eventId,
		String correlationId,
		String occurredAt,
		String message) {
}
