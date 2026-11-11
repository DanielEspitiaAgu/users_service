package co.edu.uptc.users_service.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaMessagingConfiguration {
	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> kafkaTemplate) {
		var deadLetterRecoverer = new DeadLetterPublishingRecoverer(
				kafkaTemplate,
				(record, exception) -> new TopicPartition("poc.ping.dlq", 0));
		var errorHandler = new DefaultErrorHandler(
				deadLetterRecoverer,
				new FixedBackOff(0L, 0L));
		errorHandler.setCommitRecovered(true);
		return errorHandler;
	}
}
