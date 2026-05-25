package com.nexus.fraud.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TriggeringFactor(
        @JsonProperty("category")
        String category,
        @JsonProperty("description")
        String description,
        @JsonProperty("weight")
        BigDecimal weight,
        @JsonProperty("evidence")
        String evidence
) {}

