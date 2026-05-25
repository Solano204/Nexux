package com.nexus.account.infrastructure.persistence;

import com.nexus.account.domain.model.DailyLimitUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DailyLimitUsageRepository — Data access for daily transaction limits.
 *
 * One row per account per day. Composite PK: (account_id, usage_date).
 *
 * Midnight cleanup job deletes entries older than retention period
 * (typically 90 days for compliance, configurable).
 *
 * This is more efficient than resetting daily_transaction_used on
 * every account row at midnight — only accounts that actually
 * transacted today have rows here.
 */
@Repository
public interface DailyLimitUsageRepository
        extends JpaRepository<DailyLimitUsage, DailyLimitUsage.DailyLimitUsageId> {

    /**
     * Get today's usage for an account.
     * Returns empty if no transactions today (no row created yet).
     */
    Optional<DailyLimitUsage> findByAccountIdAndUsageDate(
            UUID accountId, LocalDate usageDate);

    /**
     * Usage history for an account within a date range.
     * Used by analytics and compliance reporting.
     */
    List<DailyLimitUsage> findByAccountIdAndUsageDateBetweenOrderByUsageDateDesc(
            UUID accountId, LocalDate from, LocalDate to);

    /**
     * All usage entries for a specific date — admin monitoring.
     */
    List<DailyLimitUsage> findByUsageDateOrderByAmountUsedDesc(LocalDate usageDate);

    /**
     * Accounts that exceeded a threshold on a given day.
     * Used for compliance flagging and risk assessment.
     */
    @Query("""
        SELECT d FROM DailyLimitUsage d
        WHERE d.usageDate = :date
        AND d.amountUsed >= :threshold
        ORDER BY d.amountUsed DESC
        """)
    List<DailyLimitUsage> findHighUsageAccounts(
            @Param("date") LocalDate date,
            @Param("threshold") BigDecimal threshold);

    /**
     * Total amount transacted across all accounts for a day.
     * Used for platform-wide analytics dashboard.
     */
    @Query("""
        SELECT COALESCE(SUM(d.amountUsed), 0)
        FROM DailyLimitUsage d
        WHERE d.usageDate = :date
        """)
    BigDecimal sumAmountUsedByDate(@Param("date") LocalDate date);

    /**
     * Total transaction count across all accounts for a day.
     */
    @Query("""
        SELECT COALESCE(SUM(d.transactionCount), 0)
        FROM DailyLimitUsage d
        WHERE d.usageDate = :date
        """)
    long sumTransactionCountByDate(@Param("date") LocalDate date);

    /**
     * Cleanup: delete entries older than retention period.
     * Called by midnight scheduled job.
     */
    @Modifying
    @Query("DELETE FROM DailyLimitUsage d WHERE d.usageDate < :before")
    int deleteEntriesBefore(@Param("before") LocalDate before);
}