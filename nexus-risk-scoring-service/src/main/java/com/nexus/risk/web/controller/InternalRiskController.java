package com.nexus.risk.web.controller;

import com.nexus.risk.agent.model.RiskScoringAgent;
import com.nexus.risk.application.batch.NightlyRiskScoringJobTriggerService;
import com.nexus.risk.infrastructure.jpa.RiskProfileRepository;
import com.nexus.risk.infrastructure.redis.RiskProfileCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Internal Risk Controller — admin + inter-service endpoints.
 *
 * No public API — all endpoints are internal.
 *
 * GET  /internal/v1/risk/profiles/{userId}        → current risk profile
 * GET  /internal/v1/risk/profiles/{userId}/tier    → quick tier lookup (Redis)
 * GET  /internal/v1/risk/profiles/{userId}/history → profile version history
 * POST /internal/v1/risk/profiles/{userId}/compute → manual recomputation
 * POST /internal/v1/risk/batch/trigger             → manual batch trigger
 * GET  /internal/v1/risk/batch/status              → batch job status
 * GET  /internal/v1/risk/stats                     → platform risk distribution
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1/risk")
@RequiredArgsConstructor
public class InternalRiskController {

    private final RiskProfileRepository profileRepository;
    private final RiskProfileCacheService cacheService;
    private final RiskScoringAgent riskScoringAgent;
    private final NightlyRiskScoringJobTriggerService triggerService;

    @GetMapping("/profiles/{userId}")
    public ResponseEntity<?> getCurrentProfile(
            @PathVariable String userId) {
        return profileRepository.findLatestByUserId(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/profiles/{userId}/tier")
    public ResponseEntity<Map<String, String>> getRiskTier(
            @PathVariable String userId) {
        String tier = cacheService.getRiskTier(userId);
        if (tier == null) {
            return profileRepository.findLatestByUserId(userId)
                    .map(p -> ResponseEntity.ok(Map.of(
                            "userId", userId,
                            "riskTier", p.getRiskTier())))
                    .orElse(ResponseEntity.ok(Map.of(
                            "userId", userId,
                            "riskTier", "UNKNOWN")));
        }
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "riskTier", tier));
    }

    @GetMapping("/profiles/{userId}/history")
    public ResponseEntity<?> getProfileHistory(
            @PathVariable String userId) {
        return ResponseEntity.ok(
                profileRepository.findByUserIdOrderByComputedAtDesc(
                        UUID.fromString(userId)));
    }

    @PostMapping("/profiles/{userId}/compute")
    public ResponseEntity<?> triggerComputation(
            @PathVariable String userId) {
        try {
            var profile = riskScoringAgent.computeRiskProfile(
                    userId, "MANUAL");
            return ResponseEntity.ok(Map.of(
                    "status", "COMPUTED",
                    "userId", userId,
                    "overallRiskScore", profile.overallRiskScore(),
                    "riskTier", profile.riskTier().name(),
                    "confidence", profile.confidenceLevel()));
        } catch (Exception e) {
            log.error("Manual computation failed: userId={}", userId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "FAILED",
                    "userId", userId,
                    "error", e.getMessage()));
        }
    }

    @PostMapping("/batch/trigger")
    public ResponseEntity<Map<String, Object>> triggerBatch() {
        return ResponseEntity.ok(triggerService.triggerManualBatch());
    }

    @GetMapping("/batch/status")
    public ResponseEntity<Map<String, Object>> getBatchStatus() {
        return ResponseEntity.ok(triggerService.getBatchStatus());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "veryLow", profileRepository.countByRiskTier("VERY_LOW"),
                "low", profileRepository.countByRiskTier("LOW"),
                "medium", profileRepository.countByRiskTier("MEDIUM"),
                "high", profileRepository.countByRiskTier("HIGH"),
                "veryHigh", profileRepository.countByRiskTier("VERY_HIGH"),
                "candidatesForRecomputation",
                triggerService.getRecomputationCandidates().size()));
    }
}