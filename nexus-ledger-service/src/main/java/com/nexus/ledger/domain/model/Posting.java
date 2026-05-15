package com.nexus.ledger.domain.model;

import com.nexus.ledger.domain.model.enums.*;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Posting — The atomic double-entry unit.
 *
 * A posting groups exactly two (or more, always even) ledger entries
 * that together represent one financial event.
 *
 * The most critical constraint: total_debit MUST EQUAL total_credit.
 * Enforced at THREE layers:
 * 1. LedgerCommandService.validatePostingBalance() — application
 * 2. PostgreSQL CHECK constraint chk_posting_balanced — database
 * 3. Nightly reconciliation job — operational verification
 */
@Entity
@Table(name = "postings")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "postingId")
public class Posting {

    @Id
    @Column(name = "posting_id", updatable = false)
    private UUID postingId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_type", nullable = false)
    private PostingType postingType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostingStatus status;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "total_debit", nullable = false,
            precision = 20, scale = 4)
    private BigDecimal totalDebit;

    @Column(name = "total_credit", nullable = false,
            precision = 20, scale = 4)
    private BigDecimal totalCredit;

    @Column(name = "is_balanced", nullable = false)
    private boolean isBalanced;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column
    private String description;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "posted_by_service")
    private String postedByService;

    @Column(name = "trace_id")
    private String traceId;

    @Version
    private int version;

    @PrePersist
    void prePersist() {
        if (postingId == null) postingId = UUID.randomUUID();
        if (postedAt == null) postedAt = Instant.now();
        if (postedByService == null)
            postedByService = "ledger-service";
        if (status == null) status = PostingStatus.POSTED;
    }

    public void markReversed() {
        this.status = PostingStatus.REVERSED;
    }
}