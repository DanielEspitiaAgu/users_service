package co.edu.uptc.users_service.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class KafkaPingConsumer {
	private static final Logger logger = LoggerFactory.getLogger(KafkaPingConsumer.class);
	private static final String PING_TOPIC = "poc.ping";
	private static final String DLQ_TOPIC = "poc.ping.dlq";

	private final PingEventValidator validator;
	private final KafkaTemplate<String, byte[]> kafkaTemplate;
	private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

	public KafkaPingConsumer(
			PingEventValidator validator,
			KafkaTemplate<String, byte[]> kafkaTemplate) {
		this.validator = validator;
		this.kafkaTemplate = kafkaTemplate;
	}

	@KafkaListener(
			topics = PING_TOPIC,
			groupId = "${KAFKA_GROUP_ID:usuarios-service}")
	public void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment)
			throws InterruptedException, ExecutionException, TimeoutException {
		String value = record.value();
		PingEvent event;
		try {
			if (value == null) {
				throw new IllegalArgumentException("Ping event value must not be null");
			}
			event = validator.parse(value);
		} catch (IllegalArgumentException exception) {
			String reason = exception.getMessage() == null
					? "Invalid ping event"
					: exception.getMessage();
			RecordHeaders headers = new RecordHeaders();
			headers.add(new RecordHeader("reason", reason.getBytes(StandardCharsets.UTF_8)));
			ProducerRecord<String, byte[]> deadLetter = new ProducerRecord<>(
					DLQ_TOPIC,
					null,
					null,
					record.key(),
					value.getBytes(StandardCharsets.UTF_8),
					headers);
			kafkaTemplate.send(deadLetter).get(10, TimeUnit.SECONDS);
			logger.warn("Invalid Kafka event sent to {}: {}", DLQ_TOPIC, reason);
			acknowledgment.acknowledge();
			return;
		}

		if (processedEventIds.add(event.eventId())) {
			logger.info(
					"ping recibido eventId={} correlationId={}",
					event.eventId(),
					event.correlationId());
		} else {
			logger.warn("Duplicate ping ignored eventId={}", event.eventId());
		}
		acknowledgment.acknowledge();
	}
}
