package com.nexus.analytics.domain.model.enums;


public enum AnomalyType {
    SPIKE,          // Spending significantly above historical norm
    DROP,           // Spending significantly below (could signal income drop)
    NEW_MERCHANT,   // First time at an expensive merchant
    LARGE_SINGLE,   // One transaction much larger than usual
    NEGATIVE_SAVINGS // Spending exceeded income this period
}