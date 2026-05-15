package com.nexus.ledger.application.command;

import com.nexus.ledger.domain.model.enums.PostingType;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Post Ledger Command — Input to the double-entry posting service.
 * Populated from PostLedgerCommand Kafka message.
 */
@Builder
public record PostLedgerCommand(
        UUID transactionId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        PostingType postingType,
        String description,
        String merchantName,
        String merchantCategoryCode,
        String sagaId,
        String traceId
) {}