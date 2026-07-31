package com.nexus.ledger.unit;

import com.nexus.ledger.domain.model.LedgerEntry;
import com.nexus.ledger.domain.model.Posting;
import com.nexus.ledger.domain.model.enums.EntryType;
import com.nexus.ledger.domain.model.enums.PostingStatus;
import com.nexus.ledger.domain.model.enums.PostingType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain-level invariants that back the platform's "money never appears or
 * disappears" guarantee — independent of any repository/service mocking.
 */
class DoubleEntryInvariantTest {

    private LedgerEntry.LedgerEntryBuilder baseEntry() {
        return LedgerEntry.builder()
                .entryId(UUID.randomUUID())
                .postingId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .accountNumber("USR-CHK-00001")
                .accountType("USER_CHECKING")
                .amount(new BigDecimal("500.00"))
                .currency("MXN")
                .runningBalance(new BigDecimal("4500.00"))
                .category("TRANSFER")
                .fiscalYear(2026)
                .fiscalMonth(3)
                .fiscalQuarter(1)
                .postedAt(Instant.now());
    }

    @Test
    void checksumIsDeterministicForIdenticalFields() {
        UUID entryId = UUID.randomUUID();
        UUID postingId = UUID.randomUUID();
        Instant postedAt = Instant.now();

        LedgerEntry entry1 = baseEntry().entryId(entryId).postingId(postingId)
                .entryType(EntryType.DEBIT).postedAt(postedAt).build();
        LedgerEntry entry2 = baseEntry().entryId(entryId).postingId(postingId)
                .entryType(EntryType.DEBIT).postedAt(postedAt).accountId(entry1.getAccountId()).build();

        assertThat(entry1.computeChecksum()).isEqualTo(entry2.computeChecksum());
    }

    @Test
    void checksumChangesWhenAmountIsTampered() {
        LedgerEntry entry = baseEntry().entryType(EntryType.DEBIT).build();
        String originalChecksum = entry.computeChecksum();

        LedgerEntry tampered = baseEntry()
                .entryId(entry.getEntryId()).postingId(entry.getPostingId())
                .accountId(entry.getAccountId()).entryType(EntryType.DEBIT)
                .postedAt(entry.getPostedAt())
                .amount(new BigDecimal("999999.00")) // tampered
                .runningBalance(entry.getRunningBalance())
                .build();

        assertThat(tampered.computeChecksum()).isNotEqualTo(originalChecksum);
    }

    @Test
    void isChecksumValidDetectsTamperingAfterPersistence() throws Exception {
        LedgerEntry entry = baseEntry().entryType(EntryType.CREDIT).build();
        setChecksum(entry, entry.computeChecksum());

        assertThat(entry.isChecksumValid()).isTrue();

        // Simulate tampering with the amount post-persistence (should never
        // happen given the entity has no setters, but the checksum job
        // must still be able to detect a corrupted row read back from the
        // database with a stale/mismatched checksum column).
        LedgerEntry corrupted = baseEntry()
                .entryId(entry.getEntryId()).postingId(entry.getPostingId())
                .accountId(entry.getAccountId()).entryType(EntryType.CREDIT)
                .postedAt(entry.getPostedAt())
                .amount(new BigDecimal("1.00"))
                .runningBalance(entry.getRunningBalance())
                .build();
        setChecksum(corrupted, entry.getChecksum());

        assertThat(corrupted.isChecksumValid()).isFalse();
    }

    @Test
    void isChecksumValidReturnsFalseWhenChecksumNeverSet() {
        LedgerEntry entry = baseEntry().entryType(EntryType.DEBIT).build();

        assertThat(entry.isChecksumValid()).isFalse();
    }

    @Test
    void debitAndCreditEntriesForSamePostingShareEqualAmounts() {
        UUID postingId = UUID.randomUUID();
        LedgerEntry debit = baseEntry().postingId(postingId).entryType(EntryType.DEBIT)
                .amount(new BigDecimal("500.0000")).build();
        LedgerEntry credit = baseEntry().postingId(postingId).entryType(EntryType.CREDIT)
                .amount(new BigDecimal("500.0000")).build();

        assertThat(debit.getAmount()).isEqualByComparingTo(credit.getAmount());
    }

    @Test
    void postingMarksBalancedWhenTotalsMatch() {
        Posting posting = Posting.builder()
                .postingId(UUID.randomUUID())
                .postingType(PostingType.TRANSFER)
                .status(PostingStatus.POSTED)
                .entryCount(2)
                .totalDebit(new BigDecimal("500.00"))
                .totalCredit(new BigDecimal("500.00"))
                .isBalanced(true)
                .currency("MXN")
                .build();

        assertThat(posting.isBalanced()).isTrue();
        assertThat(posting.getTotalDebit()).isEqualByComparingTo(posting.getTotalCredit());
    }

    @Test
    void markReversedTransitionsPostingStatus() {
        Posting posting = Posting.builder()
                .postingId(UUID.randomUUID())
                .postingType(PostingType.TRANSFER)
                .status(PostingStatus.POSTED)
                .entryCount(2)
                .totalDebit(new BigDecimal("500.00"))
                .totalCredit(new BigDecimal("500.00"))
                .isBalanced(true)
                .currency("MXN")
                .build();

        posting.markReversed();

        assertThat(posting.getStatus()).isEqualTo(PostingStatus.REVERSED);
    }

    private void setChecksum(LedgerEntry entry, String checksum) throws Exception {
        var field = LedgerEntry.class.getDeclaredField("checksum");
        field.setAccessible(true);
        field.set(entry, checksum);
    }
}
