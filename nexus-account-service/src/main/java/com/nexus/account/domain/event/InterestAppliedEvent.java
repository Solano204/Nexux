package com.nexus.account.domain.event;
import java.math.BigDecimal;
import java.util.UUID;
public record InterestAppliedEvent(UUID accountId, BigDecimal interestAmount,
                                   BigDecimal balanceBefore, BigDecimal balanceAfter) {}