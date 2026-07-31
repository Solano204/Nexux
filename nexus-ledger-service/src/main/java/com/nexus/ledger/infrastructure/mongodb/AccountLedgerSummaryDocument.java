package com.nexus.ledger.infrastructure.mongodb;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * CQRS Read Model — Pre-aggregated account ledger summary.
 * Updated asynchronously after each PostgreSQL posting commit.
 * Provides sub-millisecond reads for dashboard and AI explainer.
 */
@Document(collection = "account_ledger_summary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountLedgerSummaryDocument {

    @Id
    private String accountId;

    // AccountLedgerSummaryRepository.findByUserId() is a real, active
    // query - without this, every call was a full collection scan.
    @Indexed
    private String userId;
    private String accountNumber;
    private String accountType;
    private String currency;
    private BigDecimal currentBalance;
    private Long lastEntryNumber;
    private Instant lastPostingAt;

    private List<MonthlySummary> monthlySummaries;

    public record MonthlySummary(
            int year,
            int month,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            BigDecimal netChange,
            int transactionCount
    ) {}
}