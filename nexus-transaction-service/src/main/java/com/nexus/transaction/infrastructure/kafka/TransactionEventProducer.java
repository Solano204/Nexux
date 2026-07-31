package com.nexus.transaction.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * TransactionEventProducer — publishes transaction lifecycle events to Kafka.
 *
 * Topics produced to:
 * - transactions.initiated     (consumed by Kafka Streams velocity topology)
 * - transactions.completed     (consumed by notification, analytics, risk-scoring)
 * - transactions.failed        (consumed by notification, analytics)
 * - saga.commands              (consumed by account-service, fraud-service)
 *
 * Producer config: acks=all, idempotence=true, max.in.flight=1
 */
@Slf4j
@Component
public class TransactionEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Counter publishedCounter;
    private final Counter publishFailedCounter;

    public TransactionEventProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            Propagator propagator,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;
        this.propagator = propagator;

        this.publishedCounter = Counter.builder("transaction.kafka.published.total")
                .description("Total events published to Kafka")
                .register(meterRegistry);
        this.publishFailedCounter = Counter.builder("transaction.kafka.publish.failed.total")
                .description("Failed Kafka publishes")
                .register(meterRegistry);
    }

    /**
     * Publish a transaction event to a specific topic.
     * Key is the transactionId for partition affinity.
     */
    public void publish(String topic, UUID transactionId, Object payload) {
        Observation obs = Observation.createNotStarted(
                        "kafka.publish", observationRegistry)
                .lowCardinalityKeyValue("topic", topic)
                .start();

        try {
            String key = transactionId.toString();
            obs.highCardinalityKeyValue("kafka.key", key);
            String value = objectMapper.writeValueAsString(payload);

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            KafkaTracePropagation.injectTraceHeaders(tracer, propagator, record);

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(record);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    publishFailedCounter.increment();
                    log.error("Failed to publish to {}: txnId={} error={}",
                            topic, transactionId, ex.getMessage());
                } else {
                    publishedCounter.increment();
                    log.debug("Published to {}: txnId={} partition={} offset={}",
                            topic, transactionId,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });

            obs.event(Observation.Event.of("kafka.published"));
        } catch (Exception e) {
            publishFailedCounter.increment();
            obs.error(e);
            log.error("Failed to serialize event for topic {}: {}",
                    topic, e.getMessage());
        } finally {
            obs.stop();
        }
    }

    public void publishTransactionInitiated(UUID transactionId, Map<String, Object> payload) {
        publish("transactions.initiated", transactionId, payload);
    }

    public void publishTransactionCompleted(UUID transactionId, Map<String, Object> payload) {
        publish("transactions.completed", transactionId, payload);
    }

    public void publishTransactionFailed(UUID transactionId, Map<String, Object> payload) {
        publish("transactions.failed", transactionId, payload);
    }

    public void publishSagaCommand(UUID sagaId, Map<String, Object> command) {
        publish("saga.commands", sagaId, command);
    }
}