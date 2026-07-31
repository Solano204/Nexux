package com.nexus.identity.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.web.dto.request.KycResultRequest;
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KYC Result Consumer — receives the verification decision from
 * nexus-ai-kyc-service, replacing the synchronous, non-retried HTTP
 * callback the old /internal/v1/users/{userId}/kyc/result endpoint used
 * to handle. See CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section
 * 6: ai-kyc-service now publishes via its own Outbox+Debezium (topic
 * identity.kyc.result), so a delivery hiccup at the exact moment of the
 * old HTTP POST can no longer silently drop an already-completed
 * verification — the message durably waits in Kafka until this consumer
 * successfully processes it.
 *
 * Tolerant Reader (Section 4): JsonNode + .path() with defaults, not a
 * rigid readValue(KycResultRequest.class) — the same platform-wide
 * convention every other consumer on this platform uses.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KycResultConsumer {

    private final UserCommandService commandService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    @KafkaListener(
            topics = "identity.kyc.result",
            groupId = "identity-service-kyc-result",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeKycResult(ConsumerRecord<String, String> record,
                                 Acknowledgment ack) {
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "identity-service-kyc-result",
                "identity.kyc.result receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            consumeTraced(record.value(), ack);
        } finally {
            span.end();
        }
    }

    private void consumeTraced(String message, Acknowledgment ack) {
        Observation obs = Observation.createNotStarted(
                        "kafka.message.processed", observationRegistry)
                .lowCardinalityKeyValue("topic", "identity.kyc.result")
                .lowCardinalityKeyValue("consumerGroup", "identity-service-kyc-result")
                .start();

        String userId = null;
        try {
            JsonNode event = objectMapper.readTree(message);

            userId = event.path("userId").asText();
            String verificationId = event.path("verificationId").asText();
            boolean approved = event.path("approved").asBoolean(false);

            KycResultRequest result = new KycResultRequest(
                    verificationId,
                    approved,
                    asMap(event.path("extractedData")),
                    asMap(event.path("verificationDecision")),
                    asStringList(event.path("failureReasons")));

            String traceId = tracer.currentSpan() != null
                    ? tracer.currentSpan().context().traceId() : "no-trace";

            commandService.processKycResult(
                    UUID.fromString(userId), UUID.fromString(verificationId),
                    result, traceId);

            ack.acknowledge();
            obs.event(Observation.Event.of("kafka.message.success"));

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process KYC result: userId={} error={}", userId, e.getMessage(), e);
            // Rethrow so KafkaConfig's DefaultErrorHandler(deadLetterRecoverer,
            // FixedBackOff) actually sees this failure and applies the bounded
            // 3-retry-then-DLT policy, instead of an unbounded wait for a
            // restart/rebalance to redeliver.
            throw new RuntimeException("Failed to process KYC result", e);
        } finally {
            obs.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        return objectMapper.convertValue(node, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        return objectMapper.convertValue(node, List.class);
    }
}
