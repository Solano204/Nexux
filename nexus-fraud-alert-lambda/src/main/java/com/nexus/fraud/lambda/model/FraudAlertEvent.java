package com.nexus.fraud.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * FraudAlertEvent — the high-severity fraud alert from nexus-fraud-service.
 *
 * Published by the fraud service when riskScore >= 85.
 * Flows: Kafka fraud.flagged → Kafka-SQS bridge →
 *        nexus-fraud-alerts-high-severity SQS → this Lambda.
 *
 * @JsonIgnoreProperties: forward compatible as fraud service evolves.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudAlertEvent(
        @JsonProperty("alertId")
        String alertId,

        @JsonProperty("transactionId")
        String transactionId,

        @JsonProperty("userId")
        String userId,

        @JsonProperty("sourceAccountId")
        String sourceAccountId,

        @JsonProperty("targetAccountId")
        String targetAccountId,

        @JsonProperty("amount")
        BigDecimal amount,

        @JsonProperty("currency")
        String currency,

        @JsonProperty("transactionType")
        String transactionType,

        @JsonProperty("riskScore")
        BigDecimal riskScore,

        @JsonProperty("alertCategory")
        String alertCategory,

        @JsonProperty("recommendedAction")
        String recommendedAction,

        @JsonProperty("sourceService")
        String sourceService,

        @JsonProperty("traceId")
        String traceId,

        @JsonProperty("fraudDecision")
        FraudDecisionSummary fraudDecision,

        @JsonProperty("triggeringFactors")
        List<TriggeringFactor> triggeringFactors
) {}

