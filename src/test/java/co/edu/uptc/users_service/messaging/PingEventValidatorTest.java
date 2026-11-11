package co.edu.uptc.users_service.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class PingEventValidatorTest {
	private final PingEventValidator validator = new PingEventValidator(new ObjectMapper());

	@Test
	void acceptsPingEventV1() {
		PingEvent event = validator.parse("""
				{"eventId":"4c8f73e2-f4b4-4c64-8d55-19300f0a0001","correlationId":"c-1",
				"occurredAt":"2026-11-11T13:00:00Z","payload":{"message":"ping"}}
				""");

		assertEquals("c-1", event.correlationId());
	}

	@Test
	void rejectsMalformedAndAdditionalFields() {
		assertThrows(IllegalArgumentException.class, () -> validator.parse("{"));
		assertThrows(
				IllegalArgumentException.class,
				() -> validator.parse("""
						{"eventId":"4c8f73e2-f4b4-4c64-8d55-19300f0a0001",
						"correlationId":"c-1","occurredAt":"2026-11-11T13:00:00Z",
						"payload":{"message":"ping"},"extra":true}
						"""));
	}
}
