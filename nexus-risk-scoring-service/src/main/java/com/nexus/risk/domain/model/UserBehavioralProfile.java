package com.nexus.risk.domain.model;

import com.nexus.risk.domain.model.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record UserBehavioralProfile(
        BigDecimal meanTransactionAmount,
        BigDecimal stdDevTransactionAmount,
        String primaryIncomeSource,
        BigDecimal typicalMonthlyIncome,
        BigDecimal typicalMonthlyExpenses,
        Map<String, Double> spendingCategoryWeights,
        List<String> topMerchantCategories,
        List<String> frequentCounterparties,
        List<String> knownDeviceFingerprints,
        List<String> knownLocations,
        String preferredTransactionTime,
        boolean hasSavingsPattern,
        boolean hasDebtPaymentPattern
) {}