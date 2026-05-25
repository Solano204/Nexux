package com.nexus.fraud.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.fraud.agent.FraudReActAgent;
import com.nexus.fraud.domain.model.*;
import com.nexus.fraud.domain.model.enums.FraudDecisionOutcome;
import com.nexus.fraud.domain.model.enums.RecommendedAction;
import com.nexus.fraud.infrastructure.persistence.FraudDecisionRepository;
import com.nexus.fraud.infrastructure.persistence.OutboxRepository;
import com.nexus.fraud.infrastructure.redis.FraudRedisRepository;
import com.nexus.fraud.web.dto.FraudAnalysisRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Fraud Analysis Service — Orchestrates the full analysis pipeline.
 *
 * Responsibilities:
 * 1. Idempotency check (existing decision for transactionId)
 * 2. Hard rule pre-screening (blacklists, Tor, suspended accounts)
 * 3. Delegate to FraudReActAgent for full AI analysis
 * 4. Persist FraudDecision to PostgreSQL
 * 5. Update Redis caches (recent decisions, flagged accounts)
 * 6. Publish high-severity alerts via outbox
 * 7. Reply to SAGA orchestrator via Kafka
 */
@Slf4j
@Service
public class FraudAnalysisService {

    private final FraudReActAgent agent;
    private final FraudDecisionRepository decisionRepository;
    private final OutboxRepository outboxRepository;
    private final FraudRedisRepository redisRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    private final Counter hardRejectCounter;

    public FraudAnalysisService(
            FraudReActAgent agent,
            FraudDecisionRepository decisionRepository,
            OutboxRepository outboxRepository,
            FraudRedisRepository redisRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.agent = agent;
        this.decisionRepository = decisionRepository;
        this.outboxRepository = outboxRepository;
        this.redisRepository = redisRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        this.hardRejectCounter = Counter.builder(
                        "fraud.hard_reject.total")
                .register(meterRegistry);
    }

    @Transactional
    public FraudDecision analyze(FraudAnalysisRequest request) {

        // ── Step 1: Idempotency check ──────────────────────────
        var existing = decisionRepository
                .findByTransactionId(UUID.fromString(
                        request.transactionId()));

        if (existing.isPresent()) {
            log.info("Idempotent fraud check replay: txnId={}",
                    request.transactionId());
            return toFraudDecision(existing.get());
        }

        // ── Step 2: Hard rule pre-screening ───────────────────
        String hardRejectReason = checkHardRules(request);

        FraudDecision decision;

        if (hardRejectReason != null) {
            // Instant reject without AI — saves time and cost
            hardRejectCounter.increment();
            log.warn("Hard rule triggered: txnId={} rule={}",
                    request.transactionId(), hardRejectReason);

            decision = buildHardRejectDecision(
                    request, hardRejectReason);
        } else {
            // ── Step 3: Full AI ReAct analysis ────────────────
            decision = agent.analyze(request);
        }

        // ── Step 4: Persist decision ───────────────────────────
        FraudDecisionEntity entity = toEntity(decision, request);
        decisionRepository.save(entity);

        // ── Step 5: Update Redis caches ────────────────────────
        redisRepository.addRecentDecision(
                request.userId(),
                request.transactionId(),
                decision.decision().name(),
                decision.riskScore());

        if (decision.isRejected() &&
                decision.riskScore().intValue() >= 70) {
            // Optionally flag the source account for review
            // (not permanent — compliance reviews)
        }

        // ── Step 6: High severity outbox event ────────────────
        if (decision.isHighSeverity()) {
            writeHighSeverityAlert(decision, request);
        }

        // ── Step 7: Publish SAGA reply ─────────────────────────
        publishSagaReply(decision, request);

        return decision;
    }

    private String checkHardRules(FraudAnalysisRequest request) {
        // Check merchant blacklist
        if (request.merchantId() != null &&
                redisRepository.isMerchantBlacklisted(
                        request.merchantId())) {
            return "MERCHANT_BLACKLISTED: " + request.merchantId();
        }

        // Check Tor exit node (passed in pre-signals)
        if (Boolean.TRUE.equals(request.preComputedSignals()
                .get("isTor"))) {
            return "TOR_EXIT_NODE_DETECTED";
        }

        // Check flagged source account
        if (redisRepository.isAccountFlagged(
                request.sourceAccountId())) {
            return "SOURCE_ACCOUNT_FLAGGED";
        }

        // Check flagged target account (for transfers)
        if (request.targetAccountId() != null &&
                redisRepository.isAccountFlagged(
                        request.targetAccountId())) {
            return "TARGET_ACCOUNT_FLAGGED";
        }

        return null; // No hard rule triggered
    }

    private FraudDecision buildHardRejectDecision(
            FraudAnalysisRequest request, String reason) {

        Instant now = Instant.now();
        return new FraudDecision(
                request.transactionId(),
                FraudDecisionOutcome.REJECT,
                new java.math.BigDecimal("100"),
                new java.math.BigDecimal("1.000"),
                java.util.List.of(new FraudDecision.TriggeringFactor(
                        "HARD_RULE",
                        reason,
                        java.math.BigDecimal.ONE,
                        reason,
                        "hard_reject_rules"
                )),
                java.util.List.of(),
                "Transaction rejected by hard rule: " + reason +
                        ". No AI analysis required.",
                java.util.List.of(),
                java.util.List.of(new FraudDecision.ToolCallSummary(
                        "hard_rule_check", reason, 0, true)),
                RecommendedAction.BLOCK_ACCOUNT_TEMPORARY,
                null,
                java.util.Map.of("hard_reject_reason", reason),
                now, now, 0L
        );
    }

    @Transactional
    protected void publishSagaReply(FraudDecision decision,
                                    FraudAnalysisRequest request) {
        try {
            String replyType = switch (decision.decision()) {
                case APPROVE -> "FraudClearedReply";
                case REJECT -> "FraudRejectedReply";
                case REVIEW -> "FraudReviewReply";
            };

            java.util.Map<String, Object> reply = java.util.Map.of(
                    "replyType", replyType,
                    "sagaId", request.sagaId(),
                    "transactionId", request.transactionId(),
                    "sourceService", "nexus-fraud-service",
                    "decision", decision.decision().name(),
                    "fraudScore", decision.riskScore().toPlainString(),
                    "reasons", decision.triggeringFactors().stream()
                            .map(FraudDecision.TriggeringFactor::category)
                            .toList(),
                    "traceId", request.traceId()
            );

            kafkaTemplate.send("saga.replies",
                    request.sagaId(),
                    objectMapper.writeValueAsString(reply));

            log.info("SAGA reply sent: type={} txnId={} decision={}",
                    replyType, request.transactionId(), decision.decision());

        } catch (Exception e) {
            log.error("Failed to publish SAGA reply: {}",
                    e.getMessage(), e);
        }
    }

    private void writeHighSeverityAlert(FraudDecision decision,
                                        FraudAnalysisRequest request) {
        try {
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("transactionId", request.transactionId())
                    .put("userId", request.userId())
                    .put("sourceAccountId", request.sourceAccountId())
                    .put("riskScore", decision.riskScore().toPlainString())
                    .put("decision", decision.decision().name())
                    .put("recommendedAction",
                            decision.recommendedAction().name())
                    .put("alertedAt", Instant.now().toString());

            var reasons = objectMapper.createArrayNode();
            decision.triggeringFactors().forEach(f ->
                    reasons.add(f.category()));
            payload.set("triggeringFactors", reasons);

            outboxRepository.save(OutboxEntry.of(
                    "FRAUD_ALERT",
                    UUID.fromString(request.transactionId()),
                    "FraudHighSeverityAlert",
                    payload));

        } catch (Exception e) {
            log.error("Failed to write high severity alert: {}",
                    e.getMessage());
        }
    }

    private FraudDecisionEntity toEntity(FraudDecision d,
                                         FraudAnalysisRequest req) {
        try {
            return FraudDecisionEntity.builder()
                    .decisionId(UUID.randomUUID())
                    .transactionId(UUID.fromString(d.transactionId()))
                    .userId(UUID.fromString(req.userId()))
                    .sourceAccountId(UUID.fromString(req.sourceAccountId()))
                    .targetAccountId(req.targetAccountId() != null
                            ? UUID.fromString(req.targetAccountId()) : null)
                    .amount(req.amount())
                    .currency(req.currency())
                    .transactionType(req.transactionType())
                    .decisionOutcome(d.decision().name())
                    .riskScore(d.riskScore())
                    .confidenceLevel(d.confidenceLevel())
                    .recommendedAction(d.recommendedAction().name())
                    .reviewPriority(d.reviewPriority())
                    .triggeringFactors(
                            objectMapper.valueToTree(d.triggeringFactors()))
                    .clearingFactors(
                            objectMapper.valueToTree(d.clearingFactors()))
                    .reasoning(d.reasoning())
                    .policyCitations(
                            objectMapper.valueToTree(d.policyCitations()))
                    .toolCallSummary(
                            objectMapper.valueToTree(d.toolCallSummary()))
                    .rawSignals(objectMapper.valueToTree(d.rawSignals()))
                    .toolsCalled(d.toolCallSummary() != null
                            ? d.toolCallSummary().stream()
                            .map(FraudDecision.ToolCallSummary::toolName)
                            .toList()
                            : java.util.List.of())
                    .analysisStartedAt(d.analysisStartedAt())
                    .analysisCompletedAt(d.analysisCompletedAt())
                    .analysisTimeMs(d.analysisTimeMs() != null
                            ? d.analysisTimeMs().intValue() : 0)
                    .modelUsed("gpt-4o-mini")
                    .traceId(req.traceId())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to build entity from decision", e);
        }
    }

    private FraudDecision toFraudDecision(FraudDecisionEntity e) {
        return new FraudDecision(
                e.getTransactionId().toString(),
                FraudDecisionOutcome.valueOf(e.getDecisionOutcome()),
                e.getRiskScore(),
                e.getConfidenceLevel(),
                java.util.List.of(), java.util.List.of(),
                e.getReasoning(),
                java.util.List.of(), java.util.List.of(),
                RecommendedAction.valueOf(e.getRecommendedAction()),
                e.getReviewPriority(),
                java.util.Map.of(),
                e.getAnalysisStartedAt(),
                e.getAnalysisCompletedAt(),
                e.getAnalysisTimeMs() != null
                        ? e.getAnalysisTimeMs().longValue() : 0L
        );
    }
}