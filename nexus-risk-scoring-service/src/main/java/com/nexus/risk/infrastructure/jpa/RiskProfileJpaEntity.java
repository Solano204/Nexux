package com.nexus.risk.infrastructure.jpa;

import com.nexus.risk.domain.model.*;
import com.nexus.risk.domain.model.enums.RiskTier;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RiskProfileJpaEntity — PostgreSQL persistence for risk profiles.
 *
 * Maps to risk_profiles table (V1 migration).
 * Component scores stored as JSONB — flexible schema, no migration
 * needed when scoring model evolves.
 * tools_used and data_gaps stored as TEXT[] (PostgreSQL array).
 * @Version for optimistic locking during concurrent event-triggered updates.
 */
@Entity
@Table(name = "risk_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskProfileJpaEntity {

    @Id
    @Column(name = "profile_id")
    private UUID profileId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(nullable = false)
    private int version;

    @Column(name = "overall_risk_score", nullable = false)
    private int overallRiskScore;

    @Column(name = "risk_tier", nullable = false)
    private String riskTier;

    @Column(name = "confidence_level", nullable = false,
            precision = 4, scale = 3)
    private BigDecimal confidenceLevel;

    @Type(JsonType.class)
    @Column(name = "credit_risk", columnDefinition = "jsonb",
            nullable = false)
    private CreditRiskScore creditRisk;

    @Type(JsonType.class)
    @Column(name = "behavioral_risk", columnDefinition = "jsonb",
            nullable = false)
    private BehavioralRiskScore behavioralRisk;

    @Type(JsonType.class)
    @Column(name = "compliance_risk", columnDefinition = "jsonb",
            nullable = false)
    private ComplianceRiskScore complianceRisk;

    @Type(JsonType.class)
    @Column(name = "velocity_profile", columnDefinition = "jsonb",
            nullable = false)
    private VelocityRiskProfile velocityProfile;

    @Type(JsonType.class)
    @Column(name = "behavioral_profile", columnDefinition = "jsonb",
            nullable = false)
    private UserBehavioralProfile behavioralProfile;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "agent_plan_summary")
    private String agentPlanSummary;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tools_used", columnDefinition = "text[]")
    private List<String> toolsUsed;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "data_gaps", columnDefinition = "text[]")
    private List<String> dataGaps;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "scoring_warnings", columnDefinition = "text[]")
    private List<String> scoringWarnings;

    @Column(name = "regulatory_classification")
    private String regulatoryClassification;

    @Column(name = "computation_started_at")
    private Instant computationStartedAt;

    @Column(name = "computation_completed_at")
    private Instant computationCompletedAt;

    @Column(name = "computation_duration_ms")
    private Integer computationDurationMs;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(name = "trigger_event_id")
    private UUID triggerEventId;

    @PrePersist
    void prePersist() {
        if (profileId == null) profileId = UUID.randomUUID();
        if (computedAt == null) computedAt = Instant.now();
        if (validUntil == null) validUntil = Instant.now()
                .plus(Duration.ofHours(24));
    }

    /**
     * Factory: builds JPA entity from domain RiskProfile record.
     */
    public static RiskProfileJpaEntity from(RiskProfile profile,
                                            Instant startedAt,
                                            long durationMs) {
        return RiskProfileJpaEntity.builder()
                .profileId(profile.profileId() != null
                        ? UUID.fromString(profile.profileId())
                        : UUID.randomUUID())
                .userId(UUID.fromString(profile.userId()))
                .computedAt(profile.computedAt() != null
                        ? profile.computedAt() : Instant.now())
                .validUntil(profile.validUntil() != null
                        ? profile.validUntil()
                        : Instant.now().plus(Duration.ofHours(24)))
                .version(profile.version())
                .overallRiskScore(profile.overallRiskScore())
                .riskTier(profile.riskTier() != null
                        ? profile.riskTier().name() : "MEDIUM")
                .confidenceLevel(BigDecimal.valueOf(
                        profile.confidenceLevel()))
                .creditRisk(profile.creditRisk())
                .behavioralRisk(profile.behavioralRisk())
                .complianceRisk(profile.complianceRisk())
                .velocityProfile(profile.velocityProfile())
                .behavioralProfile(profile.behavioralProfile())
                .modelVersion(profile.modelVersion())
                .agentPlanSummary(profile.agentPlanSummary())
                .toolsUsed(profile.toolsUsed())
                .dataGaps(profile.dataGaps())
                .scoringWarnings(profile.scoringWarnings())
                .regulatoryClassification(
                        profile.regulatoryClassification())
                .computationStartedAt(startedAt)
                .computationCompletedAt(Instant.now())
                .computationDurationMs((int) durationMs)
                .build();
    }
}