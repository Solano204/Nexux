package com.nexus.risk.domain.model;

import com.nexus.risk.domain.model.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;



public record BehavioralRiskScore(
        int score,
        double spendingVolatility,
        double incomeConsistency,
        double savingsRate,
        double counterpartyDiversity,
        boolean hasRecurringPayments,
        int dormancyDays,
        List<String> behavioralFlags
) {}
