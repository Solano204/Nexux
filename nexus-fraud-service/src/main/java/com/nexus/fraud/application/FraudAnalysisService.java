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
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

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
    private final Tracer tracer;
    private final Propagator propagator;
    private final SqsClient sqsClient;

    @Value("${nexus.aws.fraud-alert-queue-url:}")
    private String fraudAlertQueueUrl;

    private final Counter hardRejectCounter;

    public FraudAnalysisService(
            FraudReActAgent agent,
            FraudDecisionRepository decisionRepository,
            OutboxRepository outboxRepository,
            FraudRedisRepository redisRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            Propagator propagator,
            MeterRegistry meterRegistry,
            SqsClient sqsClient) {

        this.agent = agent;
        this.decisionRepository = decisionRepository;
        this.outboxRepository = outboxRepository;
        this.redisRepository = redisRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;
        this.propagator = propagator;
        this.sqsClient = sqsClient;

        this.hardRejectCounter = Counter.builder(
                        "fraud.hard_reject.total")
                .register(meterRegistry);
    }

    @Transactional
    public FraudDecision analyze(FraudAnalysisRequest request) {

        // Tags the active span (the saga.commands receive span opened by
        // FraudCommandConsumer) rather than creating a new Observation here -
        // this class has no ObservationRegistry of its own, and the caller's
        // span is already what's visible in Zipkin for this whole operation.
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag("transaction.id", request.transactionId());
            currentSpan.tag("user.id", request.userId());
        }

        // ── Step 1: Idempotency check ──────────────────────────
        var existing = decisionRepository
                .findByTransactionId(UUID.fromString(
                        request.transactionId()));

        if (existing.isPresent()) {
            log.info("Idempotent fraud check replay: txnId={}",
                    request.transactionId());
            if (currentSpan != null) {
                currentSpan.tag("operation.result", "idempotent_replay");
                currentSpan.tag("nexus.idempotency.duplicate", "true");
            }
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

        if (currentSpan != null) {
            currentSpan.tag("fraud.decision", decision.decision().name());
            currentSpan.tag("fraud.risk_score", decision.riskScore().toString());
            currentSpan.tag("operation.result",
                    decision.isRejected() ? "rejected" : "cleared");
        }

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

            java.util.List<String> reasonList = decision.triggeringFactors().stream()
                    .map(FraudDecision.TriggeringFactor::category)
                    .toList();

            // riskScore + fraudScore: both keys for compatibility
            // (saga orchestrator reads riskScore, transaction service reads fraudScore)
            java.util.Map<String, Object> reply = new java.util.HashMap<>();
            reply.put("replyType", replyType);
            reply.put("sagaId", request.sagaId());
            reply.put("transactionId", request.transactionId());
            reply.put("sourceService", "nexus-fraud-service");
            reply.put("decision", decision.decision().name());
            reply.put("riskScore", decision.riskScore().toPlainString());
            reply.put("fraudScore", decision.riskScore().toPlainString());
            reply.put("reasons", reasonList);
            reply.put("traceId", request.traceId());

            ProducerRecord<String, String> replyRecord = new ProducerRecord<>(
                    "saga.replies", request.sagaId(), objectMapper.writeValueAsString(reply));
            KafkaTracePropagation.injectTraceHeaders(tracer, propagator, replyRecord);
            kafkaTemplate.send(replyRecord);

            log.info("SAGA reply sent: type={} txnId={} decision={}",
                    replyType, request.transactionId(), decision.decision());

            // Write fraud.result outbox for ALL decisions — audit indexing picks this up
            writeFraudResultEvent(decision, request, replyType, reasonList);

        } catch (Exception e) {
            log.error("Failed to publish SAGA reply: {}",
                    e.getMessage(), e);
        }
    }

    private void writeFraudResultEvent(FraudDecision decision,
                                       FraudAnalysisRequest request,
                                       String replyType,
                                       java.util.List<String> reasons) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode payload =
                    objectMapper.createObjectNode()
                            .put("transactionId", request.transactionId())
                            .put("userId", request.userId())
                            .put("sourceAccountId", request.sourceAccountId())
                            .put("riskScore", decision.riskScore().toPlainString())
                            .put("decision", decision.decision().name())
                            .put("replyType", replyType)
                            .put("recommendedAction", decision.recommendedAction().name())
                            .put("reasoning", decision.reasoning())
                            .put("traceId", request.traceId())
                            .put("analyzedAt", Instant.now().toString());

            var reasonsArray = objectMapper.createArrayNode();
            reasons.forEach(reasonsArray::add);
            payload.set("reasons", reasonsArray);

            OutboxEntry resultEntry = OutboxEntry.of(
                    "fraud.result",
                    UUID.fromString(request.transactionId()),
                    "FraudDecision",
                    payload);
            resultEntry.attachTraceContext(tracer);
            outboxRepository.save(resultEntry);

        } catch (Exception e) {
            log.warn("Failed to write fraud.result outbox event: {}", e.getMessage());
        }
    }

    private void writeHighSeverityAlert(FraudDecision decision,
                                        FraudAnalysisRequest request) {
        try {
            String alertId = UUID.randomUUID().toString();
            Instant now = Instant.now();

            var triggeringFactorsList = objectMapper.createArrayNode();
            decision.triggeringFactors().forEach(f ->
                    triggeringFactorsList.add(f.category()));

            ObjectNode payload = objectMapper.createObjectNode()
                    .put("transactionId", request.transactionId())
                    .put("userId", request.userId())
                    .put("sourceAccountId", request.sourceAccountId())
                    .put("riskScore", decision.riskScore().toPlainString())
                    .put("decision", decision.decision().name())
                    .put("recommendedAction", decision.recommendedAction().name())
                    .put("alertedAt", now.toString());
            payload.set("triggeringFactors", triggeringFactorsList);

            OutboxEntry alertEntry = OutboxEntry.of(
                    "fraud.flagged",
                    UUID.fromString(request.transactionId()),
                    "FraudHighSeverityAlert",
                    payload);
            alertEntry.attachTraceContext(tracer);
            outboxRepository.save(alertEntry);

            if (!fraudAlertQueueUrl.isBlank()) {
                publishFraudAlertToSqs(alertId, decision, request, now);
            }

        } catch (Exception e) {
            log.error("Failed to write high severity alert: {}", e.getMessage());
        }
    }

    private void publishFraudAlertToSqs(String alertId, FraudDecision decision,
                                        FraudAnalysisRequest request, Instant now) {
        try {
            // triggeringFactors: full objects — lambda expects List<TriggeringFactor> with category/description/weight/evidence
            var triggeringFactors = objectMapper.createArrayNode();
            decision.triggeringFactors().forEach(f -> {
                ObjectNode factor = objectMapper.createObjectNode()
                        .put("category", f.category())
                        .put("description", f.description())
                        .put("evidence", f.evidence() != null ? f.evidence() : "");
                if (f.weight() != null) factor.put("weight", f.weight());
                triggeringFactors.add(factor);
            });

            // fraudDecision: full object — lambda expects FraudDecisionSummary with outcome/riskScore/reasoning
            ObjectNode fraudDecisionNode = objectMapper.createObjectNode()
                    .put("outcome", decision.decision().name())
                    .put("reasoning", decision.reasoning() != null ? decision.reasoning() : "");
            if (decision.riskScore() != null) fraudDecisionNode.put("riskScore", decision.riskScore());
            if (decision.confidenceLevel() != null) fraudDecisionNode.put("confidenceLevel", decision.confidenceLevel());

            ObjectNode event = objectMapper.createObjectNode()
                    .put("alertId", alertId)
                    .put("transactionId", request.transactionId())
                    .put("userId", request.userId())
                    .put("sourceAccountId", request.sourceAccountId())
                    .put("targetAccountId", request.targetAccountId())
                    // amount/riskScore as numeric BigDecimal — lambda deserializes to BigDecimal, string would fail
                    .put("amount", request.amount() != null ? request.amount() : java.math.BigDecimal.ZERO)
                    .put("currency", request.currency())
                    .put("transactionType", request.transactionType())
                    .put("riskScore", decision.riskScore())
                    .put("alertCategory", "HIGH_SEVERITY_FRAUD")
                    .put("recommendedAction", decision.recommendedAction().name())
                    .put("sourceService", "nexus-fraud-service")
                    .put("traceId", request.traceId())
                    .put("detectedAt", now.toString());
            event.set("fraudDecision", fraudDecisionNode);
            event.set("triggeringFactors", triggeringFactors);

            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(fraudAlertQueueUrl)
                    .messageBody(objectMapper.writeValueAsString(event))
                    .build());

            log.info("Fraud alert published to SQS: alertId={} txnId={} score={}",
                    alertId, request.transactionId(), decision.riskScore());

        } catch (Exception e) {
            log.error("Failed to publish fraud alert to SQS — outbox fallback active: {}",
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