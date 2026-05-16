package com.nexus.risk.domain.model;

import com.nexus.risk.domain.model.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;


public record CreditRiskScore(
        int score,                      // 300-850
        CreditGrade grade,
        double probabilityOfDefault,
        double incomeStability,
        double debtServiceCapacity,
        BigDecimal estimatedMonthlyIncome,
        BigDecimal estimatedMonthlyExpenses,
        BigDecimal netCashFlow,
        int monthsOfHistory,
        List<String> positiveCreditSignals,
        List<String> negativeCreditSignals
) {}
