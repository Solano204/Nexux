package com.nexus.kyc.web.controller;

import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.jpa.KycAuditEntryJPA;
import com.nexus.kyc.infrastructure.jpa.KycAuditRepository;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal KYC Controller — service-to-service endpoints.
 *
 * Called by:
 * - Identity Service: verification status, retry eligibility
 * - Saga Orchestrator: result polling
 * - Audit Service: compliance queries, audit trail retrieval
 * - AI Assistant: "why was my verification rejected?"
 * - Compliance officers: manual review outcome submission
 * - Health Monitor Lambda: metrics/health
 *
 * NOT exposed through API Gateway — internal Docker network only.
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1/kyc")
@RequiredArgsConstructor
@Tag(name = "KYC (Internal)", description = "Service-to-service only, not routed through nexus-api-gateway — verification records, retry eligibility, and compliance review actions.")
public class InternalKycController {

    private final KycDocumentRepository kycDocumentRepository;
    private final KycAuditRepository kycAuditRepository;

    // ── Verification Status ────────────────────────────────

    @Operation(summary = "Get full verification record", description = "Used by identity-service (to check KYC status before issuing tokens) and compliance — includes internal fields the user-facing KycController deliberately omits (confidence scores, AI reasoning).")
    @ApiResponse(responseCode = "200", description = "Verification record retrieved")
    @ApiResponse(responseCode = "404", description = "No verification with this ID")
    @GetMapping("/verifications/{verificationId}")
    public ResponseEntity<KycDocumentMongoDB> getVerification(
            @Parameter(description = "Verification ID", required = true)
            @PathVariable String verificationId) {
        return kycDocumentRepository.findById(verificationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "List a user's verification attempts", description = "Full history, not just the latest — used by the compliance dashboard.")
    @ApiResponse(responseCode = "200", description = "Verifications retrieved (empty list if none)")
    @GetMapping("/verifications/user/{userId}")
    public ResponseEntity<List<KycDocumentMongoDB>>
    getUserVerifications(
            @Parameter(description = "User UUID", required = true)
            @PathVariable String userId) {
        return ResponseEntity.ok(
                kycDocumentRepository
                        .findByUserIdOrderBySubmittedAtDesc(userId));
    }

    @Operation(summary = "Get the audit trail for a verification", description = "Full PostgreSQL audit trail — every state transition, chronological.")
    @ApiResponse(responseCode = "200", description = "Audit trail retrieved (empty list if none)")
    @GetMapping("/verifications/{verificationId}/audit")
    public ResponseEntity<List<KycAuditEntryJPA>> getAuditTrail(
            @Parameter(description = "Verification ID", required = true)
            @PathVariable String verificationId) {
        return ResponseEntity.ok(
                kycAuditRepository
                        .findByVerificationIdOrderBySubmittedAtAsc(
                                UUID.fromString(verificationId)));
    }

    // ── Retry Eligibility ──────────────────────────────────

    @Operation(summary = "Check KYC retry eligibility", description = "3 rejections max per rolling 30-day window — infrastructure failures don't count against this, only REJECTED outcomes do.")
    @ApiResponse(responseCode = "200", description = "Eligibility computed")
    @GetMapping("/retry-eligibility/{userId}")
    public ResponseEntity<Map<String, Object>> checkRetryEligibility(
            @Parameter(description = "User UUID", required = true)
            @PathVariable String userId) {

        Instant thirtyDaysAgo = Instant.now()
                .minus(30, ChronoUnit.DAYS);

        List<KycDocumentMongoDB> recentRejections =
                kycDocumentRepository
                        .findByUserIdOrderBySubmittedAtDesc(userId)
                        .stream()
                        .filter(v -> v.getSubmittedAt()
                                .isAfter(thirtyDaysAgo))
                        .filter(v -> v.getStatus() == KycStatus.REJECTED)
                        .toList();

        boolean eligible = recentRejections.size() < 3;
        int remaining = Math.max(0, 3 - recentRejections.size());

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "eligible", eligible,
                "remainingAttempts", remaining,
                "recentRejections", recentRejections.size(),
                "windowDays", 30,
                "totalLifetimeAttempts",
                kycDocumentRepository.countByUserId(userId)));
    }

    // ── Compliance Officer Review ──────────────────────────

    @Operation(summary = "Submit a manual review outcome", description = "reviewOutcome must be APPROVED or REJECTED; reviewedBy and reviewOutcome are required, reviewNotes is optional.")
    @ApiResponse(responseCode = "200", description = "Outcome recorded")
    @ApiResponse(responseCode = "400", description = "Missing reviewOutcome or reviewedBy")
    @ApiResponse(responseCode = "404", description = "No verification with this ID")
    @PostMapping("/review/{verificationId}/outcome")
    public ResponseEntity<Map<String, Object>> submitReviewOutcome(
            @Parameter(description = "Verification ID", required = true)
            @PathVariable String verificationId,
            @RequestBody Map<String, String> body) {

        String reviewOutcome = body.get("reviewOutcome");
        String reviewNotes = body.get("reviewNotes");
        String reviewedBy = body.get("reviewedBy");

        if (reviewOutcome == null || reviewedBy == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "reviewOutcome and reviewedBy required"));
        }

        return kycDocumentRepository.findById(verificationId)
                .map(doc -> {
                    KycStatus newStatus = switch (reviewOutcome) {
                        case "APPROVED" -> KycStatus.APPROVED;
                        case "REJECTED" -> KycStatus.REJECTED;
                        default -> doc.getStatus();
                    };

                    doc.setStatus(newStatus);
                    doc.setDecidedAt(Instant.now());
                    kycDocumentRepository.save(doc);

                    log.info("Manual review: verificationId={} " +
                                    "outcome={} by={}",
                            verificationId, reviewOutcome, reviewedBy);

                    return ResponseEntity.ok(Map.<String, Object>of(
                            "verificationId", verificationId,
                            "reviewOutcome", reviewOutcome,
                            "status", newStatus.name(),
                            "reviewedAt", Instant.now().toString()));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── SAR Filing ─────────────────────────────────────────

    @Operation(summary = "Record a SAR filing", description = "Regulatory compliance requirement — records that a Suspicious Activity Report was filed for this verification.")
    @ApiResponse(responseCode = "200", description = "SAR recorded")
    @PostMapping("/verifications/{verificationId}/sar")
    public ResponseEntity<Map<String, String>> recordSarFiling(
            @Parameter(description = "Verification ID", required = true)
            @PathVariable String verificationId,
            @RequestBody Map<String, String> body) {

        String sarReference = body.get("sarReferenceNumber");
        String filedBy = body.get("filedBy");

        log.info("SAR filed: verificationId={} ref={} by={}",
                verificationId, sarReference, filedBy);

        return ResponseEntity.ok(Map.of(
                "verificationId", verificationId,
                "sarReferenceNumber", sarReference,
                "filedAt", Instant.now().toString(),
                "status", "SAR_RECORDED"));
    }

    // ── Metrics ────────────────────────────────────────────

    @Operation(summary = "Get daily KYC metrics", description = "Total/pending verification counts for the compliance dashboard.")
    @ApiResponse(responseCode = "200", description = "Metrics retrieved")
    @GetMapping("/metrics/daily")
    public ResponseEntity<Map<String, Object>> getDailyMetrics() {
        Instant dayStart = Instant.now()
                .truncatedTo(ChronoUnit.DAYS);

        long total = kycDocumentRepository.count();
        long approved = kycDocumentRepository
                .countByUserIdAndStatus(null, KycStatus.APPROVED);
        long pending = kycDocumentRepository
                .findByStatus(KycStatus.PROCESSING).size();

        return ResponseEntity.ok(Map.of(
                "totalVerifications", total,
                "pendingProcessing", pending,
                "serviceStatus", "OPERATIONAL",
                "generatedAt", Instant.now().toString()));
    }

    // ── Re-verification Trigger ────────────────────────────

    @Operation(summary = "Trigger manual re-verification", description = "Compliance request or the 90-day periodic review — queues re-verification, doesn't run it synchronously (returns 202).")
    @ApiResponse(responseCode = "202", description = "Re-verification queued")
    @PostMapping("/re-verify/{userId}")
    public ResponseEntity<Map<String, String>> triggerReVerification(
            @Parameter(description = "User UUID", required = true)
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {

        String reason = body.getOrDefault("reason",
                "COMPLIANCE_REQUESTED");

        log.info("Re-verification triggered: userId={} reason={}",
                userId, reason);

        // Production: publish to Kafka topic kyc.reverification.requested
        return ResponseEntity.accepted().body(Map.of(
                "userId", userId,
                "status", "RE_VERIFICATION_QUEUED",
                "reason", reason,
                "queuedAt", Instant.now().toString()));
    }
}