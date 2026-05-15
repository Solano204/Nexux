package com.nexus.ledger.infrastructure.persistence;

import com.nexus.ledger.domain.model.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * LedgerEntry Repository — Append-only reads only.
 * No save() calls with existing entities — the service only inserts.
 *
 * Critical query: findLatestRunningBalance uses the
 * (account_id, entry_number DESC) index for O(1) current balance.
 */
@Repository
public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, UUID> {

    /**
     * Current balance — uses (account_id, entry_number DESC) index.
     * Returns in milliseconds via LIMIT 1 index scan.
     */
    @Query("""
        SELECT le.runningBalance FROM LedgerEntry le
        WHERE le.accountId = :accountId
        ORDER BY le.entryNumber DESC
        LIMIT 1
        """)
    Optional<BigDecimal> findLatestRunningBalance(
            @Param("accountId") UUID accountId);

    /**
     * Historical balance at a specific point in time.
     * Used for state reconstruction and temporal queries.
     */
    @Query("""
        SELECT COALESCE(
            SUM(CASE WHEN le.entryType = 'CREDIT'
                THEN le.amount ELSE -le.amount END),
            0)
        FROM LedgerEntry le
        WHERE le.accountId = :accountId
        AND le.postedAt <= :atTime
        """)
    BigDecimal computeBalanceAtTime(
            @Param("accountId") UUID accountId,
            @Param("atTime") Instant atTime);

    /**
     * All entries for a posting (both debit and credit sides).
     */
    List<LedgerEntry> findByPostingIdOrderByEntryNumberAsc(
            UUID postingId);

    /**
     * Recent entries for an account — paginated.
     */
    Page<LedgerEntry> findByAccountIdOrderByEntryNumberDesc(
            UUID accountId, Pageable pageable);

    /**
     * Entries by transaction ID (all sides of a transaction).
     */
    List<LedgerEntry> findByTransactionIdOrderByEntryNumberAsc(
            UUID transactionId);

    /**
     * Entries for a fiscal month (monthly statement generation).
     */
    @Query("""
        SELECT le FROM LedgerEntry le
        WHERE le.accountId = :accountId
        AND le.fiscalYear = :year
        AND le.fiscalMonth = :month
        ORDER BY le.entryNumber ASC
        """)
    List<LedgerEntry> findByAccountAndFiscalPeriod(
            @Param("accountId") UUID accountId,
            @Param("year") int year,
            @Param("month") int month);

    /**
     * Category breakdown for a date range.
     */
    @Query("""
        SELECT le.category,
               le.entryType,
               SUM(le.amount) as total,
               COUNT(le.entryId) as count
        FROM LedgerEntry le
        WHERE le.accountId = :accountId
        AND le.postedAt BETWEEN :startDate AND :endDate
        GROUP BY le.category, le.entryType
        """)
    List<Object[]> getCategoryBreakdown(
            @Param("accountId") UUID accountId,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate);

    /**
     * Global double-entry integrity check.
     * Sum of all debits MUST equal sum of all credits for any period.
     */
    @Query("""
        SELECT
            SUM(CASE WHEN le.entryType = 'DEBIT'
                THEN le.amount ELSE 0 END) as totalDebits,
            SUM(CASE WHEN le.entryType = 'CREDIT'
                THEN le.amount ELSE 0 END) as totalCredits
        FROM LedgerEntry le
        WHERE le.postedAt >= :from AND le.postedAt < :to
        """)
    Object[] computeGlobalBalance(
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Sample entries for checksum verification (10% sample).
     */
    @Query(value = """
        SELECT * FROM ledger_entries
        WHERE posted_at >= :since
        ORDER BY RANDOM()
        LIMIT :sampleSize
        """, nativeQuery = true)
    List<LedgerEntry> sampleForIntegrityCheck(
            @Param("since") Instant since,
            @Param("sampleSize") int sampleSize);
}