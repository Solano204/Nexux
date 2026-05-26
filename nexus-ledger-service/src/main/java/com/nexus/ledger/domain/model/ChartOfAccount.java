package com.nexus.ledger.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Chart of Accounts — Registry of all valid ledger accounts.
 *
 * User accounts: USR-CHK-{id}, USR-SAV-{id}, USR-INV-{id}
 * System accounts: SYS-FEE-REVENUE, SYS-INTEREST-EXPENSE,
 *   SYS-TRANSFER-TRANSIT, SYS-SUSPENSE, SYS-FRAUD-RESERVE,
 *   SYS-OPENING-BALANCE, SYS-REGULATORY-HOLD
 *
 * normal_balance: DEBIT for assets/expenses, CREDIT for liabilities/revenue/equity
 */
@Entity
@Table(name = "chart_of_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "accountId")
public class ChartOfAccount {

    @Id
    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Column(name = "account_type", nullable = false)
    private String accountType;

    @Column(name = "account_subtype", nullable = false)
    private String accountSubtype;

    @Column(name = "normal_balance", nullable = false)
    private String normalBalance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "is_user_account")
    private boolean isUserAccount;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "is_active")
    private boolean isActive;

    @Column(name = "opening_balance", precision = 20, scale = 4)
    private BigDecimal openingBalance;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @PrePersist
    void prePersist() {
        if (accountId == null) accountId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (currency == null) currency = "MXN";
        if (openingBalance == null) openingBalance = BigDecimal.ZERO;
        isActive = true;
    }
}