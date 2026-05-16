package com.nexus.risk.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.risk.domain.model.RiskProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * Publishes risk.profile.updated after every successful computation.
     * Consumed by: Fraud Service, Audit Service, Analytics Service.
     */
    public void publishProfileUpdated(RiskProfile profile,
                                      String previousTier) {
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

            kafkaTemplate.send("risk.profile.updated",
                    profile.userId(),
                    objectMapper.writeValueAsString(event));

            log.debug("risk.profile.updated published: userId={} " +
                    "tier={}", profile.userId(), profile.riskTier());

        } catch (Exception e) {
            log.error("Failed to publish risk event: userId={} {}",
                    profile.userId(), e.getMessage());
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