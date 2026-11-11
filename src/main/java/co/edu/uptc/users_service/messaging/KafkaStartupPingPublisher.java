package co.edu.uptc.users_service.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

@Component
public class KafkaStartupPingPublisher implements ApplicationRunner {
	private static final Logger logger = LoggerFactory.getLogger(KafkaStartupPingPublisher.class);
	private static final String PING_TOPIC = "poc.ping";

	private final KafkaTemplate<String, byte[]> kafkaTemplate;
	private final ObjectMapper objectMapper;
	private final boolean publishOnStart;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(
			Thread.ofVirtual().name("kafka-ping-publisher-", 0).factory());
	private volatile boolean running = true;

	public KafkaStartupPingPublisher(
			KafkaTemplate<String, byte[]> kafkaTemplate,
			ObjectMapper objectMapper,
			@Value("${KAFKA_POC_PING_ON_START:false}") boolean publishOnStart) {
		this.kafkaTemplate = kafkaTemplate;
		this.objectMapper = objectMapper;
		this.publishOnStart = publishOnStart;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		if (publishOnStart) {
			executor.submit(this::publishWithRetry);
		}
	}

	@PreDestroy
	public void stop() {
		running = false;
		executor.shutdownNow();
	}

	private void publishWithRetry() {
		long waitSeconds = 1;
		while (running && !Thread.currentThread().isInterrupted()) {
			String eventId = UUID.randomUUID().toString();
			String correlationId = UUID.randomUUID().toString();
			try {
				byte[] value = objectMapper.writeValueAsBytes(Map.of(
						"eventId", eventId,
						"correlationId", correlationId,
						"occurredAt", Instant.now().toString(),
						"payload", Map.of("message", "ping from usuarios-service")));
				kafkaTemplate.send(PING_TOPIC, correlationId, value)
						.get(10, TimeUnit.SECONDS);
				logger.info(
						"Kafka ping published eventId={} correlationId={}",
						eventId,
						correlationId);
				return;
			} catch (JsonProcessingException exception) {
				logger.error("Could not serialize startup ping", exception);
				return;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			} catch (ExecutionException | TimeoutException exception) {
				logger.warn(
						"Kafka unavailable; retrying startup ping in {}s: {}",
						waitSeconds,
						exception.getMessage());
				try {
					TimeUnit.SECONDS.sleep(waitSeconds);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
				waitSeconds = Math.min(waitSeconds * 2, 30);
			}
		}
	}

}
