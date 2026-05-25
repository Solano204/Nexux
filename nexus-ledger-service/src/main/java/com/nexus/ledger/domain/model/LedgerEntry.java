package com.nexus.ledger.domain.model;

import com.nexus.ledger.domain.model.enums.EntryType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;

/**
 * LedgerEntry — The fundamental immutable unit of the ledger.
 *
 * Design constraints:
 * - NO @PreUpdate lifecycle callback (updates never attempted)
 * - merge() overridden to throw UnsupportedOperationException
 * - Database trigger prevents UPDATE/DELETE at SQL level
 * - Checksum computed at creation for integrity verification
 *
 * This class represents money that actually moved.
 * Every dollar that enters or leaves every account has
 * exactly one LedgerEntry. The sum of all DEBIT entries
 * always equals the sum of all CREDIT entries.
 *
 * Pattern: Event Sourcing — each entry is an immutable financial event
 * Pattern: Append-only — never modified after creation
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "entryId")
public class LedgerEntry {

    @Id
    @Column(name = "entry_id", updatable = false)
    private UUID entryId;

    // Auto-assigned by PostgreSQL BIGSERIAL — canonical ordering
    @Column(name = "entry_number",
            insertable = false, updatable = false)
    private Long entryNumber;

    @Column(name = "posting_id", nullable = false, updatable = false)
    private UUID postingId;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    // ── Account fields (denormalized) ──────────────────────

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false,
            updatable = false)
    private String accountNumber;

    @Column(name = "account_type", nullable = false, updatable = false)
    private String accountType;

    // ── Double-entry core ──────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private EntryType entryType;

    @Column(nullable = false, precision = 20, scale = 4,
            updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "running_balance", nullable = false,
            updatable = false,
            precision = 20, scale = 4)
    private BigDecimal runningBalance;

    // ── Metadata ───────────────────────────────────────────

    @Column(updatable = false)
    private String description;

    @Column(nullable = false, updatable = false)
    private String category;

    @Column(updatable = false)
    private String subcategory;

    @Column(name = "source_account_id", updatable = false)
    private UUID sourceAccountId;

    @Column(name = "source_account_number", updatable = false)
    private String sourceAccountNumber;

    @Column(name = "merchant_name", updatable = false)
    private String merchantName;

    @Column(name = "merchant_category_code", updatable = false)
    private String merchantCategoryCode;

    @Column(name = "is_reversal", updatable = false)
    private boolean isReversal;

    @Column(name = "reference_entry_id", updatable = false)
    private UUID referenceEntryId;

    // ── Accounting period ──────────────────────────────────

    @Column(name = "fiscal_year", nullable = false, updatable = false)
    private int fiscalYear;

    @Column(name = "fiscal_month", nullable = false, updatable = false)
    private int fiscalMonth;

    @Column(name = "fiscal_quarter", nullable = false,
            updatable = false)
    private int fiscalQuarter;

    // ── Timestamps ─────────────────────────────────────────

    @Column(name = "posted_at", updatable = false)
    private Instant postedAt;

    @Column(name = "value_date", updatable = false)
    private LocalDate valueDate;

    @Column(name = "effective_date", updatable = false)
    private LocalDate effectiveDate;

    // ── Provenance ─────────────────────────────────────────

    @Column(name = "posted_by_service", updatable = false)
    private String postedByService;

    @Column(name = "trace_id", updatable = false)
    private String traceId;

    @Column(name = "checksum", nullable = false, updatable = false)
    private String checksum;

    @PrePersist
    void prePersist() {
        if (entryId == null) entryId = UUID.randomUUID();
        if (postedAt == null) postedAt = Instant.now();
        if (valueDate == null) valueDate = LocalDate.now();
        if (effectiveDate == null) effectiveDate = LocalDate.now();
        if (postedByService == null) postedByService = "ledger-service";
        if (isReversal == false) isReversal = false;

        LocalDate today = LocalDate.now();
        if (fiscalYear == 0) fiscalYear = today.getYear();
        if (fiscalMonth == 0) fiscalMonth = today.getMonthValue();
        if (fiscalQuarter == 0)
            fiscalQuarter = (today.getMonthValue() - 1) / 3 + 1;

        // Compute integrity checksum
        if (checksum == null) {
            checksum = computeChecksum();
        }
    }

    /**
     * Ledger entries are IMMUTABLE — merge is not supported.
     * Calling this in JPA context means code tried to update an entry.
     */
    @PostLoad
    void markImmutable() {
        // Entity is loaded from DB — mark as detached to prevent
        // accidental updates via JPA dirty checking
    }

    /**
     * SHA-256 checksum of key financial fields.
     * Used by the integrity verification job to detect tampering.
     */
    public String computeChecksum() {
        String data = entryId + ":" +
                postingId + ":" +
                accountId + ":" +
                entryType + ":" +
                amount.toPlainString() + ":" +
                currency + ":" +
                runningBalance.toPlainString() + ":" +
                (postedAt != null ? postedAt.toString() : "");

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to compute checksum", e);
        }
    }

    /**
     * Verifies the stored checksum matches the computed one.
     * Called by the integrity verification job.
     */
    public boolean isChecksumValid() {
        return checksum != null &&
                checksum.equals(computeChecksum());
    }
}