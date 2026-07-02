package com.nexus.reporting.model;

import java.math.BigDecimal;
import java.util.Map;

public record CategoryStats(
    String userId,
    String yearMonth,
    Map<String, CategoryDetail> categories
) {
    public record CategoryDetail(
        String categoryName,
        BigDecimal totalAmount,
        int transactionCount,
        String topMerchantName,
        BigDecimal topMerchantAmount
    ) {}
}
