package com.nexus.saga.domain.model;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class SagaFailureContext {

    public enum FailureType {
        FRAUD_REJECTED, INSUFFICIENT_FUNDS, KYC_REJECTED,
        SAGA_TIMEOUT, COMPENSATION_FAILED
    }

    private final FailureType failureType;
    private final String userId;
    private final BigDecimal amount;
    private final String currency;
    private final String targetName;
    private final List<String> technicalReasons;
    private final boolean fundsWereReserved;
    private final boolean fundsAreReleased;
    private final boolean canRetry;
    private final String language;
}