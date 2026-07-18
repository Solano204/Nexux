package com.nexus.identity.web.controller;

import com.nexus.identity.application.command.UnauthorizedException;
import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.web.dto.response.IdentitySummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Internal", description = "Service-to-service only — not reachable through nexus-api-gateway's public routes. Trust model varies by endpoint, see each operation's description.")
public class InternalController {

    private final UserQueryService queryService;

    // Same property name transaction-service already uses for the same
    // secret (nexus-payment-processor-lambda's bridge into
    // InternalTransactionController).
    @Value("${nexus.plane-bridge-secret:}")
    private String planeBridgeSecret;

    @Operation(
            summary = "Get identity summary (no PII)",
            description = "Used by account-service and fraud-service to verify a user exists and its " +
                    "current status, without exposing password hash or full profile. Protected by " +
                    "the gateway's RemoteAddr predicate only (Docker-network callers) — see the " +
                    "class-level note in InternalController.java for why this one doesn't also " +
                    "validate X-Plane-Bridge-Secret like getKycStatus does."
    )
    @ApiResponse(responseCode = "200", description = "Identity summary retrieved")
    @GetMapping("/users/{userId}/identity")
    public ResponseEntity<IdentitySummaryResponse> getIdentitySummary(
            @Parameter(description = "User UUID", required = true)
            @PathVariable UUID userId,
            @Parameter(description = "Caller's service name, logged for audit only — not validated")
            @RequestHeader(value = "X-Calling-Service",
                    required = false) String callingService) {

        log.debug("Internal identity check: userId={} callingService={}",
                userId, callingService);

        IdentitySummaryResponse response =
                queryService.getIdentitySummary(userId);

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Get KYC status (Lambda bridge)",
            description = "Used by nexus-auth-lambda (AWS, outside the Docker network) to check KYC " +
                    "state before issuing Cognito tokens. Requires X-Plane-Bridge-Secret — the " +
                    "gateway's RemoteAddr predicate alone isn't a real boundary for a caller outside " +
                    "the Docker network, this is the actual check for this one."
    )
    @ApiResponse(responseCode = "200", description = "Status retrieved")
    @ApiResponse(responseCode = "401", description = "Missing or invalid X-Plane-Bridge-Secret")
    @GetMapping("/users/{userId}/kyc/status")
    public ResponseEntity<Map<String, Object>> getKycStatus(
            @Parameter(description = "User UUID", required = true)
            @PathVariable UUID userId,
            @Parameter(description = "Shared secret Terraform provisions and nexus-auth-lambda sends", required = true)
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

    @Operation(summary = "Detailed health check", description = "More detailed than /actuator/health for internal service-to-service calls.")
    @ApiResponse(responseCode = "200", description = "Service is up")
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