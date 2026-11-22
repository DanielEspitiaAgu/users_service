package co.edu.uptc.users_service.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@EnableScheduling
public class MatchSagaOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(MatchSagaOutboxPublisher.class);
    private static final int MAX_BACKOFF_SECONDS = 300;

    private final MatchSagaOutboxStore outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    private int batchSize;

    private int leaseSeconds;

    private long ackTimeoutMillis;

    public MatchSagaOutboxPublisher(
            MatchSagaOutboxStore outbox, KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${events.saga-outbox.batch-size:25}") int batchSize,
            @Value("${events.saga-outbox.lease-seconds:120}") int leaseSeconds,
            @Value("${events.saga-outbox.ack-timeout-ms:10000}") long ackTimeoutMillis) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.batchSize = Math.max(1, batchSize);
        this.leaseSeconds = Math.max(1, leaseSeconds);
        this.ackTimeoutMillis = Math.max(1, ackTimeoutMillis);
        meterRegistry.gauge("match_saga_outbox_pending", outbox, MatchSagaOutboxStore::countPending);
    }

    @Scheduled(fixedDelayString = "${events.saga-outbox.poll-interval-ms:1000}",
            initialDelayString = "${events.saga-outbox.initial-delay-ms:1000}")
    public void publishPending() {
        final List<ClaimedMatchSagaOutcome> claimed;
        try {
            long sequentialBatchSeconds = ((long) batchSize * ackTimeoutMillis + 999) / 1000 + 10;
            int effectiveLeaseSeconds = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(leaseSeconds, sequentialBatchSeconds));
            claimed = outbox.claimPending(batchSize, effectiveLeaseSeconds);
        } catch (RuntimeException error) {
            meterRegistry.counter("match_saga_outbox_claim_failures").increment();
            log.error("Could not claim pending match saga confirmations: {}", error.getClass().getSimpleName());
            return;
        }

        for (ClaimedMatchSagaOutcome outcome : claimed) {
            try {
                kafkaTemplate.send(MatchFinishedListener.OUTCOME_TOPIC, outcome.matchId(), outcome.payload())
                        .get(ackTimeoutMillis, TimeUnit.MILLISECONDS);
                if (!outbox.markPublished(outcome.id(), outcome.claimToken())) {
                    log.warn("Confirmation was acknowledged but outbox lease was lost eventId={} matchId={}",
                            outcome.eventId(), outcome.matchId());
                }
                meterRegistry.counter("match_saga_outbox_published").increment();
            } catch (Exception error) {
                int delaySeconds = retryDelaySeconds(outcome.attempts());
                String errorType = error.getClass().getSimpleName();
                try {
                    outbox.scheduleRetry(outcome.id(), outcome.claimToken(), delaySeconds, errorType);
                } catch (RuntimeException updateError) {
                    log.error("Could not release match saga outbox claim eventId={} matchId={} errorType={}",
                            outcome.eventId(), outcome.matchId(), updateError.getClass().getSimpleName());
                }
                meterRegistry.counter("match_saga_outbox_publish_failures").increment();
                log.warn("Could not publish match saga confirmation eventId={} matchId={} attempt={} retryInSeconds={} errorType={}",
                        outcome.eventId(), outcome.matchId(), outcome.attempts(), delaySeconds, errorType);
            }
        }
    }

    static int retryDelaySeconds(int attempt) {
        int exponent = Math.min(10, Math.max(0, attempt - 1));
        return Math.min(MAX_BACKOFF_SECONDS, 5 * (1 << exponent));
    }
}
