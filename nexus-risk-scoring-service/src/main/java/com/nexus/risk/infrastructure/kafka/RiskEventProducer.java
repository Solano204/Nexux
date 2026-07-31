package com.nexus.risk.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.risk.domain.model.RiskProfile;
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;
    private final ObservationRegistry observationRegistry;

    /**
     * Publishes risk.profile.updated after every successful computation.
     * Consumed by: Fraud Service, Audit Service, Analytics Service.
     */
    public void publishProfileUpdated(RiskProfile profile,
                                      String previousTier) {
        Observation obs = Observation.createNotStarted(
                        "kafka.publish", observationRegistry)
                .lowCardinalityKeyValue("topic", "risk.profile.updated")
                .highCardinalityKeyValue("kafka.key", profile.userId())
                .start();
        try {
            boolean tierChanged = !profile.riskTier().name()
                    .equals(previousTier);

            Map<String, Object> event = Map.of(
                    "userId", profile.userId(),
                    "profileId", profile.profileId(),
                    "overallRiskScore", profile.overallRiskScore(),
                    "riskTier", profile.riskTier().name(),
                    "previousRiskTier", previousTier != null
                            ? previousTier : "NONE",
                    "riskTierChanged", tierChanged,
                    "riskIncreased", isRiskIncreased(
                            profile, previousTier),
                    "regulatoryClassification",
                    profile.regulatoryClassification() != null
                            ? profile.regulatoryClassification()
                            : "LOW_RISK",
                    "requiresComplianceReview",
                    profile.complianceRisk() != null &&
                            "HIGH".equals(profile.complianceRisk().riskTier()),
                    "computedAt", Instant.now().toString()
            );

            ProducerRecord<String, String> record = new ProducerRecord<>(
                    "risk.profile.updated", profile.userId(),
                    objectMapper.writeValueAsString(event));
            KafkaTracePropagation.injectTraceHeaders(tracer, propagator, record);
            kafkaTemplate.send(record);

            log.debug("risk.profile.updated published: userId={} " +
                    "tier={}", profile.userId(), profile.riskTier());

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to publish risk event: userId={} {}",
                    profile.userId(), e.getMessage());
        } finally {
            obs.stop();
        }
    }

    private boolean isRiskIncreased(RiskProfile profile,
                                    String previousTier) {
        if (previousTier == null) return false;
        try {
            var prevTier = com.nexus.risk.domain.model.enums
                    .RiskTier.valueOf(previousTier);
            return profile.riskTier().ordinal() >
                    prevTier.ordinal();
        } catch (Exception e) {
            return false;
        }
    }
}