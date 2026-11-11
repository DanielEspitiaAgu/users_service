package co.edu.uptc.users_service.messaging;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class PingEventValidator {
	private static final Set<String> EVENT_FIELDS = Set.of(
			"eventId", "correlationId", "occurredAt", "payload");
	private static final Set<String> PAYLOAD_FIELDS = Set.of("message");

	private final ObjectMapper objectMapper;

	public PingEventValidator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public PingEvent parse(String value) {
		try {
			JsonNode event = objectMapper.readTree(value);
			if (event == null || !event.isObject() || !hasOnlyFields(event, EVENT_FIELDS)) {
				throw new IllegalArgumentException("Invalid ping event fields");
			}

			String eventId = requiredText(event, "eventId");
			UUID parsedId = UUID.fromString(eventId);
			if (!parsedId.toString().equalsIgnoreCase(eventId)) {
				throw new IllegalArgumentException("eventId must be a UUID");
			}
			String correlationId = requiredText(event, "correlationId");
			if (correlationId.isBlank()) {
				throw new IllegalArgumentException("correlationId must not be blank");
			}
			String occurredAt = requiredText(event, "occurredAt");
			OffsetDateTime.parse(occurredAt);

			JsonNode payload = event.get("payload");
			if (payload == null || !payload.isObject()
					|| !hasOnlyFields(payload, PAYLOAD_FIELDS)) {
				throw new IllegalArgumentException("Invalid ping payload fields");
			}
			String message = requiredText(payload, "message");
			return new PingEvent(eventId, correlationId, occurredAt, message);
		} catch (JsonProcessingException | DateTimeParseException exception) {
			throw new IllegalArgumentException("Invalid ping event JSON or date", exception);
		}
	}

	private static boolean hasOnlyFields(JsonNode node, Set<String> fields) {
		if (node.size() != fields.size()) {
			return false;
		}
		var names = node.fieldNames();
		while (names.hasNext()) {
			if (!fields.contains(names.next())) {
				return false;
			}
		}
		return true;
	}

	private static String requiredText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isTextual()) {
			throw new IllegalArgumentException(field + " must be a string");
		}
		return value.textValue();
	}
}
