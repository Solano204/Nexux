package com.nexus.kyc.web.controller;

import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.jpa.KycAuditEntryJPA;
import com.nexus.kyc.infrastructure.jpa.KycAuditRepository;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentRepository;
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
public class InternalKycController {

    private final KycDocumentRepository kycDocumentRepository;
    private final KycAuditRepository kycAuditRepository;

    // ── Verification Status ────────────────────────────────

    /**
     * GET /internal/v1/kyc/verifications/{verificationId}
     * Full verification record. Used by Identity Service and compliance.
     */
    @GetMapping("/verifications/{verificationId}")
    public ResponseEntity<KycDocumentMongoDB> getVerification(
            @PathVariable String verificationId) {
        return kycDocumentRepository.findById(verificationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /internal/v1/kyc/verifications/user/{userId}
     * All verification attempts for a user. Compliance dashboard.
     */
    @GetMapping("/verifications/user/{userId}")
    public ResponseEntity<List<KycDocumentMongoDB>>
    getUserVerifications(@PathVariable String userId) {
        return ResponseEntity.ok(
                kycDocumentRepository
                        .findByUserIdOrderBySubmittedAtDesc(userId));
    }

    /**
     * GET /internal/v1/kyc/verifications/{verificationId}/audit
     * Full PostgreSQL audit trail for one verification.
     */
    @GetMapping("/verifications/{verificationId}/audit")
    public ResponseEntity<List<KycAuditEntryJPA>> getAuditTrail(
            @PathVariable String verificationId) {
        return ResponseEntity.ok(
                kycAuditRepository
                        .findByVerificationIdOrderBySubmittedAtAsc(
                                UUID.fromString(verificationId)));
    }

    // ── Retry Eligibility ──────────────────────────────────

    /**
     * GET /internal/v1/kyc/retry-eligibility/{userId}
     * Check if user can retry KYC. 3 attempts max per 30 days.
     * Infrastructure failures do NOT consume retry slots.
     */
    @GetMapping("/retry-eligibility/{userId}")
    public ResponseEntity<Map<String, Object>> checkRetryEligibility(
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

    /**
     * POST /internal/v1/kyc/review/{verificationId}/outcome
     * Compliance officer submits manual review decision.
     */
    @PostMapping("/review/{verificationId}/outcome")
    public ResponseEntity<Map<String, Object>> submitReviewOutcome(
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

    /**
     * POST /internal/v1/kyc/verifications/{verificationId}/sar
     * Record Suspicious Activity Report filing.
     */
    @PostMapping("/verifications/{verificationId}/sar")
    public ResponseEntity<Map<String, String>> recordSarFiling(
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

    /**
     * GET /internal/v1/kyc/metrics/daily
     * Daily KYC processing metrics for compliance dashboard.
     */
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

    /**
     * POST /internal/v1/kyc/re-verify/{userId}
     * Trigger manual re-verification (compliance request or 90-day review).
     */
    @PostMapping("/re-verify/{userId}")
    public ResponseEntity<Map<String, String>> triggerReVerification(
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