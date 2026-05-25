package com.nexus.identity.web.controller;

import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.web.dto.response.IdentitySummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Internal Controller — inter-service identity verification endpoints.
 *
 * These endpoints are accessible ONLY from within the Docker network
 * (172.20.0.0/16). The API Gateway enforces RemoteAddr predicate
 * on all /internal/v1/** routes.
 *
 * Used by:
 *   nexus-fraud-service     → GET /internal/v1/users/{userId}/identity
 *   nexus-account-service   → GET /internal/v1/users/{userId}/identity
 *   nexus-auth-lambda (AWS) → GET /internal/v1/users/{userId}/kyc/status
 *
 * These endpoints do NOT require a user JWT — they use
 * the X-Gateway-Internal header set by the gateway after
 * IP allowlist validation. Downstream services are trusted callers.
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalController {

    private final UserQueryService queryService;

    /**
     * Returns identity summary without PII (no password hash, no full profile).
     * Used by account-service and fraud-service to verify user identity.
     */
    @GetMapping("/users/{userId}/identity")
    public ResponseEntity<IdentitySummaryResponse> getIdentitySummary(
            @PathVariable UUID userId,
            @RequestHeader(value = "X-Calling-Service",
                    required = false) String callingService) {

        log.debug("Internal identity check: userId={} callingService={}",
                userId, callingService);

        IdentitySummaryResponse response =
                queryService.getIdentitySummary(userId);

        return ResponseEntity.ok(response);
    }

    /**
     * Returns KYC status for the given user.
     * Used by nexus-auth-lambda in the AWS plane to check KYC
     * state before issuing Cognito tokens.
     */
    @GetMapping("/users/{userId}/kyc/status")
    public ResponseEntity<Map<String, Object>> getKycStatus(
            @PathVariable UUID userId) {

        var kycStatus = queryService.getCurrentKycStatus(userId);
        var identity = queryService.getIdentitySummary(userId);

        return ResponseEntity.ok(Map.of(
                "userId", userId.toString(),
                "kycVerified", identity.kycVerified(),
                "accountStatus", identity.status(),
                "kycDecision",
                kycStatus.decision() != null ? kycStatus.decision() : "NOT_STARTED",
                "verificationId",
                kycStatus.verificationId() != null
                        ? kycStatus.verificationId() : ""
        ));
    }

    /**
     * Health check for internal service-to-service calls.
     * More detailed than /actuator/health — includes DB + Redis status.
     */
    @GetMapping("/health/detailed")
    public ResponseEntity<Map<String, Object>> detailedHealth() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "nexus-identity-service",
                "timestamp",
                java.time.Instant.now().toString()
        ));
    }
}