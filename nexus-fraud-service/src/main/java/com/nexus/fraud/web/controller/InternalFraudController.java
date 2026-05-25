package com.nexus.fraud.web.controller;

import com.nexus.fraud.application.FraudAnalysisService;
import com.nexus.fraud.domain.model.FraudDecisionEntity;
import com.nexus.fraud.infrastructure.persistence.FraudDecisionRepository;
import com.nexus.fraud.infrastructure.redis.FraudRedisRepository;
import com.nexus.fraud.web.dto.FraudAnalysisRequest;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal Fraud Controller — IP-restricted internal endpoints.
 *
 * All endpoints are under /internal/v1/fraud and secured by
 * SecurityConfig to accept only internal service network traffic.
 *
 * Endpoints:
 *   POST   /analyze                          — Direct fraud analysis (non-Kafka)
 *   GET    /decisions/{transactionId}         — Get decision by txnId
 *   GET    /decisions/user/{userId}           — User's fraud decisions (paginated)
 *   GET    /decisions/pending-reviews         — Pending REVIEW queue
 *   GET    /rules                             — List active fraud rules
 *   POST   /merchants/blacklist/{merchantId}  — Blacklist a merchant
 *   DELETE /merchants/blacklist/{merchantId}  — Remove from blacklist
 *   POST   /review/{decisionId}/outcome       — Record review outcome
 *   POST   /review/{decisionId}/sar           — Record SAR filing
 *   GET    /metrics                           — Real-time fraud stats
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1/fraud")
@RequiredArgsConstructor
public class InternalFraudController {

    private final FraudAnalysisService fraudAnalysisService;
    private final FraudDecisionRepository decisionRepository;
    private final FraudRedisRepository redisRepository;
    private final ObservationRegistry observationRegistry;

    // ══════════S════════════════════════════════════════════════
    // DIRECT ANALYSIS (non-Kafka trigger for testing / compliance)
    // ══════════════════════════════════════════════════════════

    /**
     * Direct fraud analysis — bypasses Kafka for emergency cases,
     * compliance dashboard "what if" analysis, and integration testing.
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeTransaction(
            @Valid @RequestBody FraudAnalysisRequest request) {

        Observation obs = Observation.createNotStarted(
                "fraud.api.analyze", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            log.info("Direct fraud analysis requested: txnId={}",
                    request.transactionId());

            var decision = fraudAnalysisService.analyze(request);

            obs.lowCardinalityKeyValue("outcome",
                    decision.decision().name());

            return ResponseEntity.ok(decision);

        } catch (Exception e) {
            obs.error(e);
            log.error("Direct fraud analysis failed: {}",
                    e.getMessage(), e);

            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Fraud analysis failed: " + e.getMessage());
            problem.setType(URI.create(
                    "https://nexus.com/errors/fraud-analysis-failed"));
            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // DECISION RETRIEVAL
    // ══════════════════════════════════════════════════════════

    /**
     * Retrieve a specific fraud decision by transaction ID.
     * Used by: Compliance dashboard, AI Assistant (explaining
     * rejections to users), Audit Service.
     */
    /**
     * Retrieve a specific fraud decision by transaction ID.
     * Used by: Compliance dashboard, AI Assistant, Audit Service.
     */
    @GetMapping("/decisions/{transactionId}")
    public ResponseEntity<?> getDecision(@PathVariable UUID transactionId) {

        var entityOpt = decisionRepository.findByTransactionId(transactionId);

        // ✅ FIXED: Separated evaluation branch allows ProblemDetail and DTOs to return via wildcard type bounds cleanly
        if (entityOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND,
                    "No fraud decision for transaction: " + transactionId);
            problem.setType(URI.create("https://nexus.com/errors/decision-not-found"));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }

        return ResponseEntity.ok(toResponse(entityOpt.get()));
    }
    /**
     * All fraud decisions for a user. Paginated, newest first.
     * Used by: Compliance officers reviewing a user's account.
     */
    @GetMapping("/decisions/user/{userId}")
    public ResponseEntity<Page<FraudDecisionResponse>> getUserDecisions(
            @PathVariable UUID userId, Pageable pageable) {

        Page<FraudDecisionResponse> decisions = decisionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::toResponse);

        return ResponseEntity.ok(decisions);
    }

    /**
     * Pending REVIEW decisions awaiting compliance officer action.
     * Sorted by priority (HIGH first) then by age (oldest first).
     */
    @GetMapping("/decisions/pending-reviews")
    public ResponseEntity<List<FraudDecisionResponse>> getPendingReviews() {

        List<FraudDecisionResponse> pending = decisionRepository
                .findPendingReviews().stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(pending);
    }

    // ══════════════════════════════════════════════════════════
    // MERCHANT BLACKLIST MANAGEMENT
    // ══════════════════════════════════════════════════════════

    /**
     * Add a merchant to the Redis blacklist.
     * Immediate effect — next transaction to this merchant is blocked.
     */
    @PostMapping("/merchants/blacklist/{merchantId}")
    public ResponseEntity<Map<String, Object>> blacklistMerchant(
            @PathVariable String merchantId,
            @RequestHeader(value = "X-User-Id", required = false)
            String operatorId) {

        redisRepository.blacklistMerchant(merchantId);

        log.warn("Merchant blacklisted: merchantId={} by={}",
                merchantId,
                operatorId != null ? operatorId : "SYSTEM");

        return ResponseEntity.ok(Map.of(
                "merchantId", merchantId,
                "action", "BLACKLISTED",
                "effectiveImmediately", true,
                "blacklistedAt", Instant.now().toString(),
                "blacklistedBy", operatorId != null
                        ? operatorId : "SYSTEM"));
    }

    /**
     * Remove a merchant from the blacklist.
     * Used if a merchant was incorrectly blacklisted.
     */
    @DeleteMapping("/merchants/blacklist/{merchantId}")
    public ResponseEntity<Map<String, Object>> unblacklistMerchant(
            @PathVariable String merchantId,
            @RequestHeader(value = "X-User-Id", required = false)
            String operatorId) {

        redisRepository.removeFromBlacklist(merchantId);

        log.info("Merchant removed from blacklist: merchantId={} by={}",
                merchantId,
                operatorId != null ? operatorId : "SYSTEM");

        return ResponseEntity.ok(Map.of(
                "merchantId", merchantId,
                "action", "REMOVED_FROM_BLACKLIST",
                "removedAt", Instant.now().toString(),
                "removedBy", operatorId != null
                        ? operatorId : "SYSTEM"));
    }

    // ══════════════════════════════════════════════════════════
    // MANUAL REVIEW OUTCOMES
    // ══════════════════════════════════════════════════════════

    /**
     * Compliance officer records their manual review outcome
     * for a REVIEW decision.
     *
     * Outcomes:
     *   CONFIRMED_FRAUD — SAGA compensates, account may be flagged
     *   CLEARED         — SAGA proceeds with transaction
     *   ESCALATED       — Further investigation needed
     */
    @PostMapping("/review/{decisionId}/outcome")
    @Transactional
    public ResponseEntity<?> recordReviewOutcome(
            @PathVariable UUID decisionId,
            @Valid @RequestBody ReviewOutcomeRequest request) {

        int updated = decisionRepository.recordReviewOutcome(
                decisionId,
                request.reviewerId(),
                request.outcome(),
                request.notes(),
                Instant.now());

        if (updated == 0) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "Decision not found or already reviewed: "
                            + decisionId);
            problem.setType(URI.create(
                    "https://nexus.com/errors/review-conflict"));
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(problem);
        }

        log.info("Review outcome recorded: decisionId={} outcome={} "
                        + "reviewerId={}",
                decisionId, request.outcome(), request.reviewerId());

        // If confirmed fraud, flag the source account
        if ("CONFIRMED_FRAUD".equals(request.outcome())) {
            decisionRepository.findById(decisionId).ifPresent(entity -> {
                redisRepository.flagAccount(
                        entity.getSourceAccountId().toString());
                log.warn("Account flagged after confirmed fraud: "
                                + "accountId={} decisionId={}",
                        entity.getSourceAccountId(), decisionId);
            });
        }

        return ResponseEntity.ok(Map.of(
                "decisionId", decisionId.toString(),
                "outcome", request.outcome(),
                "reviewedAt", Instant.now().toString()));
    }

    /**
     * Records that a Suspicious Activity Report (SAR) was filed
     * for this transaction. Regulatory compliance requirement.
     */
    @PostMapping("/review/{decisionId}/sar")
    @Transactional
    public ResponseEntity<?> recordSarFiling(
            @PathVariable UUID decisionId,
            @Valid @RequestBody SarFilingRequest request) {

        int updated = decisionRepository.recordSarFiling(
                decisionId,
                Instant.now(),
                request.sarReference());

        if (updated == 0) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND,
                    "Decision not found: " + decisionId);
            problem.setType(URI.create(
                    "https://nexus.com/errors/decision-not-found"));
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(problem);
        }

        log.info("SAR filed: decisionId={} reference={}",
                decisionId, request.sarReference());

        return ResponseEntity.ok(Map.of(
                "decisionId", decisionId.toString(),
                "sarReference", request.sarReference(),
                "sarFiledAt", Instant.now().toString()));
    }

    // ══════════════════════════════════════════════════════════
    // REAL-TIME METRICS
    // ══════════════════════════════════════════════════════════

    /**
     * Real-time fraud processing statistics.
     * Used by the Grafana dashboard backend.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getFraudMetrics() {

        Instant lastHour = Instant.now().minusSeconds(3600);
        Instant last24Hours = Instant.now().minusSeconds(86400);

        long approvesLastHour = decisionRepository
                .countByDecisionOutcomeAndCreatedAtAfter(
                        "APPROVE", lastHour);
        long rejectsLastHour = decisionRepository
                .countByDecisionOutcomeAndCreatedAtAfter(
                        "REJECT", lastHour);
        long reviewsLastHour = decisionRepository
                .countByDecisionOutcomeAndCreatedAtAfter(
                        "REVIEW", lastHour);

        long totalLastHour = approvesLastHour + rejectsLastHour
                + reviewsLastHour;

        double rejectionRate = totalLastHour > 0
                ? (double) rejectsLastHour / totalLastHour * 100 : 0;

        int pendingReviewCount = decisionRepository
                .findPendingReviews().size();

        long sarsLast24h = decisionRepository.findSarFiled().stream()
                .filter(d -> d.getSarFiledAt() != null
                        && d.getSarFiledAt().isAfter(last24Hours))
                .count();

        return ResponseEntity.ok(Map.of(
                "lastHour", Map.of(
                        "total", totalLastHour,
                        "approves", approvesLastHour,
                        "rejects", rejectsLastHour,
                        "reviews", reviewsLastHour,
                        "rejectionRatePercent",
                        BigDecimal.valueOf(rejectionRate)
                                .setScale(2, java.math.RoundingMode.HALF_UP)),
                "pendingReviewCount", pendingReviewCount,
                "sarsFiledLast24h", sarsLast24h,
                "generatedAt", Instant.now().toString()));
    }

    // ══════════════════════════════════════════════════════════
    // POLICY SEARCH (compliance officer RAG query interface)
    // ══════════════════════════════════════════════════════════

    /**
     * Compliance officer direct query to the fraud policy RAG store.
     * Returns policy fragments relevant to a natural language query.
     */
    @GetMapping("/policies/search")
    public ResponseEntity<Map<String, Object>> searchPolicies(
            @RequestParam String query) {

        log.info("Policy search requested: query='{}'", query);

        // Delegated to the RagPolicyTool directly for non-transaction queries
        // In a full implementation, this would call the tool or a dedicated
        // policy search service. For now, return guidance.
        return ResponseEntity.ok(Map.of(
                "query", query,
                "note", "Policy search available via RagPolicyTool. "
                        + "Use POST /analyze with a test transaction "
                        + "for full policy retrieval.",
                "searchedAt", Instant.now().toString()));
    }

    // ══════════════════════════════════════════════════════════
    // DTOs
    // ══════════════════════════════════════════════════════════

    /**
     * Response DTO for fraud decisions — excludes raw JSONB
     * fields for lightweight API responses. Full audit data
     * available via direct entity access.
     */
    public record FraudDecisionResponse(
            String decisionId,
            String transactionId,
            String userId,
            String sourceAccountId,
            String targetAccountId,
            BigDecimal amount,
            String currency,
            String transactionType,
            String decisionOutcome,
            BigDecimal riskScore,
            BigDecimal confidenceLevel,
            String recommendedAction,
            String reviewPriority,
            String reasoning,
            List<String> toolsCalled,
            String analysisStartedAt,
            String analysisCompletedAt,
            Integer analysisTimeMs,
            String modelUsed,
            String traceId,
            String reviewedBy,
            String reviewOutcome,
            String reviewNotes,
            String reviewedAt,
            boolean sarFiled,
            String sarReference,
            String createdAt
    ) {}

    public record ReviewOutcomeRequest(
            @NotNull UUID reviewerId,
            @NotBlank String outcome,
            String notes
    ) {}

    public record SarFilingRequest(
            @NotBlank String sarReference
    ) {}

    // ══════════════════════════════════════════════════════════
    // ENTITY → DTO MAPPING
    // ══════════════════════════════════════════════════════════

    private FraudDecisionResponse toResponse(FraudDecisionEntity e) {
        return new FraudDecisionResponse(
                e.getDecisionId().toString(),
                e.getTransactionId().toString(),
                e.getUserId().toString(),
                e.getSourceAccountId().toString(),
                e.getTargetAccountId() != null
                        ? e.getTargetAccountId().toString() : null,
                e.getAmount(),
                e.getCurrency(),
                e.getTransactionType(),
                e.getDecisionOutcome(),
                e.getRiskScore(),
                e.getConfidenceLevel(),
                e.getRecommendedAction(),
                e.getReviewPriority(),
                e.getReasoning(),
                e.getToolsCalled(),
                e.getAnalysisStartedAt() != null
                        ? e.getAnalysisStartedAt().toString() : null,
                e.getAnalysisCompletedAt() != null
                        ? e.getAnalysisCompletedAt().toString() : null,
                e.getAnalysisTimeMs(),
                e.getModelUsed(),
                e.getTraceId(),
                e.getReviewedBy() != null
                        ? e.getReviewedBy().toString() : null,
                e.getReviewOutcome(),
                e.getReviewNotes(),
                e.getReviewedAt() != null
                        ? e.getReviewedAt().toString() : null,
                e.isSarFiled(),
                e.getSarReference(),
                e.getCreatedAt() != null
                        ? e.getCreatedAt().toString() : null
        );
    }
}