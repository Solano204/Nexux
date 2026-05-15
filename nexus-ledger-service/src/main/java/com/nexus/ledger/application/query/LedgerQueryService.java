package com.nexus.ledger.application.query;

import com.nexus.ledger.domain.model.LedgerEntry;
import com.nexus.ledger.infrastructure.mongodb.*;
import com.nexus.ledger.infrastructure.persistence.LedgerEntryRepository;
import com.nexus.ledger.web.dto.response.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ledger Query Service — CQRS Read Side.
 *
 * Read path priority:
 * 1. MongoDB (current balance, monthly summary, recent entries)
 *    → sub-millisecond, pre-aggregated
 * 2. PostgreSQL (historical queries, state reconstruction)
 *    → milliseconds, precise but slower
 *
 * All reads are @Transactional(readOnly = true).
 * Never modifies data.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerQueryService {

    private final LedgerEntryRepository entryRepository;
    private final AccountLedgerSummaryRepository summaryRepository;
    private final PostingDocumentRepository postingDocRepository;
    private final ObservationRegistry observationRegistry;

    /**
     * Current balance — reads from MongoDB summary (O(1)).
     * Falls back to PostgreSQL running_balance if MongoDB unavailable.
     */
    public BigDecimal getCurrentBalance(UUID accountId) {
        return summaryRepository
                .findByAccountId(accountId.toString())
                .map(AccountLedgerSummaryDocument::getCurrentBalance)
                .orElseGet(() -> entryRepository
                        .findLatestRunningBalance(accountId)
                        .orElse(BigDecimal.ZERO));
    }

    /**
     * Recent entries — reads from MongoDB (fast) or PostgreSQL.
     */
    public List<LedgerEntryResponse> getRecentEntries(
            UUID accountId, int count) {

        return entryRepository
                .findByAccountIdOrderByEntryNumberDesc(
                        accountId, PageRequest.of(0, count))
                .getContent()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Monthly summary — reads from MongoDB pre-aggregated data.
     */
    public MonthlySummaryResponse getMonthlySummary(
            UUID accountId, int year, int month) {

        return summaryRepository
                .findByAccountId(accountId.toString())
                .flatMap(doc -> doc.getMonthlySummaries() != null
                        ? doc.getMonthlySummaries().stream()
                        .filter(s -> s.year() == year
                                && s.month() == month)
                        .findFirst()
                        .map(s -> new MonthlySummaryResponse(
                                year, month,
                                s.openingBalance(), s.closingBalance(),
                                s.totalDebits(), s.totalCredits(),
                                s.netChange(), s.transactionCount()))
                        : Optional.empty())
                .orElseGet(() ->
                        computeMonthlySummaryFromPostgres(
                                accountId, year, month));
    }

    public CategoryBreakdownResponse getCategoryBreakdown(
            UUID accountId, String startDate, String endDate) {

        Instant start = LocalDate.parse(startDate)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = LocalDate.parse(endDate)
                .atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

        List<Object[]> rawBreakdown = entryRepository
                .getCategoryBreakdown(accountId, start, end);

        List<CategoryBreakdownResponse.CategoryEntry> entries =
                rawBreakdown.stream()
                        .map(row -> new CategoryBreakdownResponse.CategoryEntry(
                                (String) row[0],  // category
                                (String) row[1],  // entryType
                                (BigDecimal) row[2], // total
                                ((Long) row[3]).intValue())) // count
                        .toList();

        return new CategoryBreakdownResponse(
                accountId.toString(), startDate, endDate, entries);
    }

    /**
     * Full entry history for an account (paginated).
     * Reads directly from PostgreSQL for precise historical data.
     */
    public List<LedgerEntryResponse> getFullHistory(
            UUID accountId, int page, int size) {
        return entryRepository
                .findByAccountIdOrderByEntryNumberDesc(
                        accountId, PageRequest.of(page, size))
                .getContent()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Complete posting (both debit + credit sides).
     */
    public PostingDetailResponse getPostingDetail(UUID postingId) {
        return postingDocRepository
                .findById(postingId.toString())
                .map(doc -> new PostingDetailResponse(
                        doc.getPostingId(),
                        doc.getTransactionId(),
                        doc.getPostingType(),
                        doc.getDescription(),
                        doc.getAmount(),
                        doc.getCurrency(),
                        doc.getPostedAt() != null
                                ? doc.getPostedAt().toString() : null))
                .orElseGet(() -> {
                    // Fallback: reconstruct from PostgreSQL
                    var entries = entryRepository
                            .findByPostingIdOrderByEntryNumberAsc(postingId);
                    if (entries.isEmpty()) return null;
                    var first = entries.get(0);
                    return new PostingDetailResponse(
                            postingId.toString(), null,
                            "TRANSFER", first.getDescription(),
                            first.getAmount(), first.getCurrency(),
                            first.getPostedAt().toString());
                });
    }

    // ── Computed fallbacks ────────────────────────────────

    private MonthlySummaryResponse computeMonthlySummaryFromPostgres(
            UUID accountId, int year, int month) {
        List<LedgerEntry> monthEntries =
                entryRepository.findByAccountAndFiscalPeriod(
                        accountId, year, month);

        BigDecimal totalDebits = monthEntries.stream()
                .filter(e -> e.getEntryType().name().equals("DEBIT"))
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = monthEntries.stream()
                .filter(e -> e.getEntryType().name().equals("CREDIT"))
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new MonthlySummaryResponse(
                year, month,
                BigDecimal.ZERO, BigDecimal.ZERO,
                totalDebits, totalCredits,
                totalCredits.subtract(totalDebits),
                monthEntries.size()
        );
    }

    private LedgerEntryResponse toResponse(LedgerEntry e) {
        return new LedgerEntryResponse(
                e.getEntryId().toString(),
                e.getPostingId().toString(),
                e.getEntryType().name(),
                e.getAmount(),
                e.getCurrency(),
                e.getRunningBalance(),
                e.getDescription(),
                e.getCategory(),
                e.getMerchantName(),
                e.getPostedAt().toString()
        );
    }
}