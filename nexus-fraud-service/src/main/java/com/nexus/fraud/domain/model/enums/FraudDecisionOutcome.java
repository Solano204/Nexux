package com.nexus.fraud.domain.model.enums;

public enum FraudDecisionOutcome {
    /** riskScore < 30 — proceed normally */
    APPROVE,
    /** 30 <= riskScore < 70 — hold for human review */
    REVIEW,
    /** riskScore >= 70 — block transaction */
    REJECT
}