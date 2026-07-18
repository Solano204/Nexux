package com.nexus.identity.web.controller;

import com.nexus.identity.application.command.UnauthorizedException;
import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.web.dto.response.IdentitySummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * getIdentitySummary is still gateway-RemoteAddr-only — no confirmed
 * caller for it sends a bridge secret, and fraud/account-service calls
 * (if they're real; no client code for either was found when this was
 * audited) come from inside the Docker network where that's a reasonable
 * boundary. getKycStatus is different: nexus-auth-lambda calls it from
 * outside the Docker network (AWS), so it now also validates
 * X-Plane-Bridge-Secret — a secret Terraform already provisions
 * (terraform/secrets.tf) and the Lambda already sends
 * (LocalPlaneBridgeClient.fetchKycStatus), this just closes the loop on
 * the service side. See CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md.
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalController {

    private final UserQueryService queryService;

    // Same property name transaction-service already uses for the same
    // secret (nexus-payment-processor-lambda's bridge into
    // InternalTransactionController).
    @Value("${nexus.plane-bridge-secret:}")
    private String planeBridgeSecret;

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
            @PathVariable UUID userId,
            @RequestHeader(value = "X-Plane-Bridge-Secret", required = false)
            String bridgeSecret) {

        requirePlaneBridgeSecret(bridgeSecret);

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

    /**
     * RemoteAddr=172.20.0.0/16 on the gateway route is the only other
     * barrier here, and nexus-auth-lambda calls from outside that range
     * (AWS) — so this is the real check for that caller, not
     * defense-in-depth. Fails closed if the secret isn't configured
     * (blank default) rather than accepting an unset-vs-unset match.
     * Constant-time comparison — this is a bearer credential.
     */
    private void requirePlaneBridgeSecret(String provided) {
        if (planeBridgeSecret == null || planeBridgeSecret.isBlank()) {
            log.error("PLANE_BRIDGE_SECRET is not configured — " +
                    "rejecting all /kyc/status bridge calls");
            throw new UnauthorizedException(
                    "Plane bridge secret not configured");
        }
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                planeBridgeSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Invalid plane bridge secret");
        }
    }
}