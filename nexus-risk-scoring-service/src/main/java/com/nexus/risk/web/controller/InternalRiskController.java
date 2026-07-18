package com.nexus.risk.web.controller;

import com.nexus.risk.agent.model.RiskScoringAgent;
import com.nexus.risk.application.batch.NightlyRiskScoringJobTriggerService;
import com.nexus.risk.infrastructure.jpa.RiskProfileRepository;
import com.nexus.risk.infrastructure.redis.RiskProfileCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Risk Profiles (Internal)", description = "Admin/ops tooling for risk profile inspection and batch triggering — no confirmed production caller, see OpenApiConfig's class description.")
@SecurityRequirement(name = "X-Internal-Service")
public class InternalRiskController {

    private final RiskProfileRepository profileRepository;
    private final RiskProfileCacheService cacheService;
    private final RiskScoringAgent riskScoringAgent;
    private final NightlyRiskScoringJobTriggerService triggerService;

    @Operation(summary = "Get current risk profile", description = "Latest computed profile — overall score, tier, contributing factors.")
    @ApiResponse(responseCode = "200", description = "Profile retrieved")
    @ApiResponse(responseCode = "404", description = "No profile computed yet for this user")
    @GetMapping("/profiles/{userId}")
    public ResponseEntity<?> getCurrentProfile(
            @Parameter(description = "User UUID", required = true)
            @PathVariable String userId) {
        return profileRepository.findLatestByUserId(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get risk tier (fast path)", description = "Redis-first, falls back to the latest profile in Postgres, falls back to UNKNOWN if neither exists — never 404s.")
    @ApiResponse(responseCode = "200", description = "Tier retrieved (UNKNOWN if no profile exists)")
    @GetMapping("/profiles/{userId}/tier")
    public ResponseEntity<Map<String, String>> getRiskTier(
            @Parameter(description = "User UUID", required = true)
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

    @Operation(summary = "Get risk profile history", description = "Every computed version for this user, newest first — for auditing how a tier changed over time.")
    @ApiResponse(responseCode = "200", description = "History retrieved (empty list if none)")
    @GetMapping("/profiles/{userId}/history")
    public ResponseEntity<?> getProfileHistory(
            @Parameter(description = "User UUID", required = true)
            @PathVariable String userId) {
        return ResponseEntity.ok(
                profileRepository.findByUserIdOrderByComputedAtDesc(
                        UUID.fromString(userId)));
    }

    @Operation(summary = "Manually trigger risk computation", description = "Synchronous — computes and returns immediately, doesn't queue. Calls OpenAI, so this has real latency and cost per call.")
    @ApiResponse(responseCode = "200", description = "Computation succeeded")
    @ApiResponse(responseCode = "500", description = "Computation failed")
    @PostMapping("/profiles/{userId}/compute")
    public ResponseEntity<?> triggerComputation(
            @Parameter(description = "User UUID", required = true)
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

    @Operation(summary = "Manually trigger the nightly batch", description = "Runs the same OpenAI-backed batch scoring job the nightly schedule runs — real cost/quota impact, this is the endpoint the resilience rate limiter (see CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md) exists to protect.")
    @ApiResponse(responseCode = "200", description = "Batch triggered")
    @PostMapping("/batch/trigger")
    public ResponseEntity<Map<String, Object>> triggerBatch() {
        return ResponseEntity.ok(triggerService.triggerManualBatch());
    }

    @Operation(summary = "Get batch job status", description = "Status of the most recent nightly (or manually triggered) batch run.")
    @ApiResponse(responseCode = "200", description = "Status retrieved")
    @GetMapping("/batch/status")
    public ResponseEntity<Map<String, Object>> getBatchStatus() {
        return ResponseEntity.ok(triggerService.getBatchStatus());
    }

    @Operation(summary = "Get platform risk tier distribution", description = "Count of users per tier, plus how many are candidates for recomputation (profile older than the recompute threshold).")
    @ApiResponse(responseCode = "200", description = "Distribution retrieved")
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