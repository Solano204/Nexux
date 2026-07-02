package com.nexus.fraud.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FraudDecisionEntity — JPA entity for the fraud_decisions audit table.
 *
 * Immutable after creation (except manual review fields).
 * Stores the full AI reasoning chain, tool call history,
 * policy citations, and raw signals for regulatory audit.
 *
 * One row per transaction (unique on transaction_id).
 */
@Entity
@Table(name = "fraud_decisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "decisionId")
public class FraudDecisionEntity {

    @Id
    @Column(name = "decision_id", updatable = false)
    private UUID decisionId;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id")
    private UUID targetAccountId;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "transaction_type", nullable = false, length = 30)
    private String transactionType;

    // ── Decision outcome ──────────────────────────────────
    @Column(name = "decision_outcome", nullable = false, length = 20)
    private String decisionOutcome;

    @Column(name = "risk_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal riskScore;

    @Column(name = "confidence_level", precision = 4, scale = 3)
    private BigDecimal confidenceLevel;

    @Column(name = "recommended_action", nullable = false, length = 50)
    private String recommendedAction;

    @Column(name = "review_priority", length = 10)
    private String reviewPriority;

    // ── AI reasoning (JSONB) ──────────────────────────────
    @Type(JsonBinaryType.class)
    @Column(name = "triggering_factors", columnDefinition = "jsonb")
    private JsonNode triggeringFactors;

    @Type(JsonBinaryType.class)
    @Column(name = "clearing_factors", columnDefinition = "jsonb")
    private JsonNode clearingFactors;

    @Column(name = "reasoning", columnDefinition = "text")
    private String reasoning;

    @Type(JsonBinaryType.class)
    @Column(name = "policy_citations", columnDefinition = "jsonb")
    private JsonNode policyCitations;

    @Type(JsonBinaryType.class)
    @Column(name = "tool_call_summary", columnDefinition = "jsonb")
    private JsonNode toolCallSummary;

    @Type(JsonBinaryType.class)
    @Column(name = "raw_signals", columnDefinition = "jsonb")
    private JsonNode rawSignals;

    @Type(JsonBinaryType.class)
    @Column(name = "analysis_plan", columnDefinition = "jsonb")
    private JsonNode analysisPlan;

    @Column(name = "tools_called", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> toolsCalled;

    // ── Timing ────────────────────────────────────────────
    @Column(name = "analysis_started_at")
    private Instant analysisStartedAt;

    @Column(name = "analysis_completed_at")
    private Instant analysisCompletedAt;

    @Column(name = "analysis_time_ms")
    private Integer analysisTimeMs;

    // ── AI model provenance ───────────────────────────────
    @Column(name = "model_used", length = 50)
    private String modelUsed;

    @Column(name = "model_version", length = 20)
    private String modelVersion;

    @Column(name = "spring_ai_version", length = 20)
    private String springAiVersion;

    @Column(name = "trace_id", length = 32)
    private String traceId;

    // ── Manual review (updatable by compliance officers) ──
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "review_outcome", length = 20)
    private String reviewOutcome;

    @Column(name = "review_notes", columnDefinition = "text")
    private String reviewNotes;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    // ── SAR tracking ──────────────────────────────────────
    @Column(name = "sar_filed")
    private boolean sarFiled;

    @Column(name = "sar_filed_at")
    private Instant sarFiledAt;

    @Column(name = "sar_reference", length = 100)
    private String sarReference;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (decisionId == null) decisionId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}