package com.nexus.reporting.model;

import java.math.BigDecimal;

public record CategoryAggregate(
    BigDecimal totalAmount,
    int transactionCount,
    int userCount
) {}
