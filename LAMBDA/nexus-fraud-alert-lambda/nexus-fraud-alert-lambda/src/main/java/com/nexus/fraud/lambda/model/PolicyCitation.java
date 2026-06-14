package com.nexus.fraud.lambda.model;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyCitation(
        @JsonProperty("policyId")
        String policyId,
        @JsonProperty("policyTitle")
        String policyTitle,
        @JsonProperty("applicationExplanation")
        String applicationExplanation
) {}
