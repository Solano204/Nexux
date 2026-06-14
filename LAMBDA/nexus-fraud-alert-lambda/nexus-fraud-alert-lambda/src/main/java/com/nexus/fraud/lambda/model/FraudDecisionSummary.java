package com.nexus.fraud.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudDecisionSummary(
        @JsonProperty("outcome")
        String outcome,
        @JsonProperty("riskScore")
        BigDecimal riskScore,
        @JsonProperty("confidenceLevel")
        BigDecimal confidenceLevel,
        @JsonProperty("reasoning")
        String reasoning,
        @JsonProperty("policyCitations")
        List<PolicyCitation> policyCitations,
        @JsonProperty("toolCallSummary")
        Map<String, String> toolCallSummary
) {}
