package com.nexus.risk.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.risk.domain.model.RiskProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Risk Profile Cache Service — two Redis keys per user.
 *
 * Key 1: risk:score:{userId}
 *   Compact summary for quick lookups.
 *   TTL: 24 hours. Used by: API Gateway, Transaction Service.
 *
 * Key 2: user:behavioral:{userId}
 *   Full UserBehavioralProfile JSON.
 *   TTL: 24 hours.
 *   Used by: Fraud Service behavioral_analysis_tool.
 *   KEY NAME MUST MATCH FRAUD SERVICE EXPECTATION.
 *
 * Key 3: user:velocity:{userId}
 *   VelocityRiskProfile JSON.
 *   TTL: 6 hours (velocity patterns more dynamic).
 *   Used by: Fraud Service velocity_check_tool.
 *
 * Key 4: risk:tier:{userId}
 *   String value (e.g., "LOW").
 *   TTL: 24 hours.
 *   Used by: API Gateway for route-level rate limiting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskProfileCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void cacheProfile(RiskProfile profile) {
        try {
            // 1. Compact risk summary
            Map<String, Object> summary = Map.of(
                    "overallScore", profile.overallRiskScore(),
                    "riskTier", profile.riskTier().name(),
                    "creditScore", profile.creditRisk() != null
                            ? profile.creditRisk().score() : 0,
                    "amlRisk", profile.complianceRisk() != null
                            ? profile.complianceRisk().amlRiskScore() : 0,
                    "behavioralRisk", profile.behavioralRisk() != null
                            ? profile.behavioralRisk().score() : 0,
                    "confidenceLevel", profile.confidenceLevel(),
                    "computedAt", profile.computedAt().toString()
            );

            String summaryKey = "risk:score:" + profile.userId();
            redisTemplate.opsForValue().set(
                    summaryKey,
                    objectMapper.writeValueAsString(summary),
                    Duration.ofHours(24));

            // 2. Full behavioral profile (Fraud Service reads this)
            if (profile.behavioralProfile() != null) {
                String behavioralKey = "user:behavioral:" +
                        profile.userId();
                redisTemplate.opsForValue().set(
                        behavioralKey,
                        objectMapper.writeValueAsString(
                                profile.behavioralProfile()),
                        Duration.ofHours(24));
            }

            // 3. Velocity profile (Fraud Service reads this too)
            if (profile.velocityProfile() != null) {
                String velocityKey = "user:velocity:" +
                        profile.userId();
                redisTemplate.opsForValue().set(
                        velocityKey,
                        objectMapper.writeValueAsString(
                                profile.velocityProfile()),
                        Duration.ofHours(6));
            }

            // 4. Quick tier for Gateway
            redisTemplate.opsForValue().set(
                    "risk:tier:" + profile.userId(),
                    profile.riskTier().name(),
                    Duration.ofHours(24));

            log.debug("Risk profile cached: userId={} tier={}",
                    profile.userId(), profile.riskTier());

        } catch (Exception e) {
            log.warn("Failed to cache risk profile: userId={} {}",
                    profile.userId(), e.getMessage());
        }
    }

    public String getRiskTier(String userId) {
        return redisTemplate.opsForValue()
                .get("risk:tier:" + userId);
    }
}