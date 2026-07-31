package com.nexus.ledger.unit;

import com.nexus.ledger.application.command.LedgerCommandService;
import com.nexus.ledger.application.command.PostLedgerCommand;
import com.nexus.ledger.domain.exception.AccountingImbalanceException;
import com.nexus.ledger.domain.model.ChartOfAccount;
import com.nexus.ledger.domain.model.enums.PostingType;
import com.nexus.ledger.infrastructure.mongodb.*;
import com.nexus.ledger.infrastructure.persistence.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class LedgerCommandServiceTest {

    @Mock LedgerEntryRepository entryRepository;
    @Mock PostingRepository postingRepository;
    @Mock ChartOfAccountRepository coaRepository;
    @Mock OutboxRepository outboxRepository;
    @Mock PostingDocumentRepository postingDocRepository;
    // AccountLedgerSummaryRepository is no longer a LedgerCommandService
    // dependency (removed from the real constructor) - MongoTemplate and
    // Propagator were added instead. Drift found while re-enabling this
    // test against the current constructor signature.
    @Mock MongoTemplate mongoTemplate;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock Tracer tracer;
    @Mock Propagator propagator;

    LedgerCommandService commandService;

    @BeforeEach
    void setUp() {
        commandService = new LedgerCommandService(
                entryRepository, postingRepository, coaRepository,
                outboxRepository, postingDocRepository, mongoTemplate,
                kafkaTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                ObservationRegistry.NOOP, tracer, propagator,
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("postDoubleEntry: idempotent — existing posting returns replay")
    void postDoubleEntry_existingPosting_returnsIdempotentResult() {
        var txnId = UUID.randomUUID();
        var existingPosting = buildPosting(txnId);

        when(postingRepository.findByTransactionId(txnId))
                .thenReturn(Optional.of(existingPosting));
        when(entryRepository.findByPostingIdOrderByEntryNumberAsc(any()))
                .thenReturn(java.util.List.of(
                        buildDebitEntry(existingPosting.getPostingId()),
                        buildCreditEntry(existingPosting.getPostingId())
                ));

        var command = buildCommand(txnId);
        var result = commandService.postDoubleEntry(command);

        assertThat(result.idempotentReplay()).isTrue();
        assertThat(result.postingId())
                .isEqualTo(existingPosting.getPostingId());

        // No new entries should be created
        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("postDoubleEntry: validates account exists in chart")
    void postDoubleEntry_unknownAccount_throwsAccountNotFoundException() {
        var txnId = UUID.randomUUID();

        when(postingRepository.findByTransactionId(txnId))
                .thenReturn(Optional.empty());
        when(coaRepository.findByAccountIdAndIsActiveTrue(any()))
                .thenReturn(Optional.empty()); // Account not found

        assertThatThrownBy(() ->
                commandService.postDoubleEntry(buildCommand(txnId))
        ).isInstanceOf(
                com.nexus.ledger.domain.exception
                        .AccountNotFoundException.class);
    }

    @Test
    @DisplayName("postDoubleEntry: rejects when source balance would go negative")
    void postDoubleEntry_insufficientBalance_throws() {
        var txnId = UUID.randomUUID();
        var command = buildCommand(txnId);
        var sourceAccount = buildChartOfAccount(command.sourceAccountId(), "USR-CHK-00001");
        var targetAccount = buildChartOfAccount(command.targetAccountId(), "USR-CHK-00002");

        when(postingRepository.findByTransactionId(txnId)).thenReturn(Optional.empty());
        when(coaRepository.findByAccountIdAndIsActiveTrue(command.sourceAccountId()))
                .thenReturn(Optional.of(sourceAccount));
        when(coaRepository.findByAccountIdAndIsActiveTrue(command.targetAccountId()))
                .thenReturn(Optional.of(targetAccount));
        when(entryRepository.findLatestRunningBalance(command.sourceAccountId()))
                .thenReturn(Optional.of(new BigDecimal("100.00"))); // less than the 500 being moved

        assertThatThrownBy(() -> commandService.postDoubleEntry(command))
                .isInstanceOf(com.nexus.ledger.domain.exception
                        .InsufficientLedgerBalanceException.class);

        verify(entryRepository, never()).save(any());
        verify(postingRepository, never()).save(any());
    }

    @Test
    @DisplayName("postDoubleEntry: happy path posts balanced debit+credit and writes outbox")
    void postDoubleEntry_validCommand_postsBalancedEntries() {
        var txnId = UUID.randomUUID();
        var command = buildCommand(txnId);
        var sourceAccount = buildChartOfAccount(command.sourceAccountId(), "USR-CHK-00001");
        var targetAccount = buildChartOfAccount(command.targetAccountId(), "USR-CHK-00002");

        when(postingRepository.findByTransactionId(txnId)).thenReturn(Optional.empty());
        when(coaRepository.findByAccountIdAndIsActiveTrue(command.sourceAccountId()))
                .thenReturn(Optional.of(sourceAccount));
        when(coaRepository.findByAccountIdAndIsActiveTrue(command.targetAccountId()))
                .thenReturn(Optional.of(targetAccount));
        when(entryRepository.findLatestRunningBalance(command.sourceAccountId()))
                .thenReturn(Optional.of(new BigDecimal("5000.00")));
        when(entryRepository.findLatestRunningBalance(command.targetAccountId()))
                .thenReturn(Optional.of(new BigDecimal("2000.00")));

        var result = commandService.postDoubleEntry(command);

        assertThat(result.idempotentReplay()).isFalse();
        assertThat(result.debitEntryId()).isNotNull();
        assertThat(result.creditEntryId()).isNotNull();

        var entryCaptor = org.mockito.ArgumentCaptor.forClass(
                com.nexus.ledger.domain.model.LedgerEntry.class);
        verify(entryRepository, times(2)).save(entryCaptor.capture());
        var entries = entryCaptor.getAllValues();
        assertThat(entries).hasSize(2);
        assertThat(entries.stream()
                .map(com.nexus.ledger.domain.model.LedgerEntry::getEntryType))
                .containsExactlyInAnyOrder(
                        com.nexus.ledger.domain.model.enums.EntryType.DEBIT,
                        com.nexus.ledger.domain.model.enums.EntryType.CREDIT);

        verify(postingRepository).save(argThat(p -> p.isBalanced()
                && p.getTotalDebit().compareTo(p.getTotalCredit()) == 0));
        verify(outboxRepository).save(argThat(e -> e.getEventType().equals("LedgerPosted")));
    }

    @Test
    @DisplayName("postReversal: swaps debit/credit sides and marks original REVERSED")
    void postReversal_validPosting_createsReversalAndMarksOriginal() {
        var originalPostingId = UUID.randomUUID();
        var original = com.nexus.ledger.domain.model.Posting.builder()
                .postingId(originalPostingId)
                .postingType(PostingType.TRANSFER)
                .status(com.nexus.ledger.domain.model.enums.PostingStatus.POSTED)
                .entryCount(2)
                .totalDebit(new BigDecimal("500.00"))
                .totalCredit(new BigDecimal("500.00"))
                .isBalanced(true)
                .currency("MXN")
                .build();
        var debit = buildDebitEntry(originalPostingId);
        var credit = buildCreditEntry(originalPostingId);

        when(postingRepository.findById(originalPostingId)).thenReturn(Optional.of(original));
        when(entryRepository.findByPostingIdOrderByEntryNumberAsc(originalPostingId))
                .thenReturn(java.util.List.of(debit, credit));
        when(postingRepository.findByTransactionId(null)).thenReturn(Optional.empty());
        when(coaRepository.findByAccountIdAndIsActiveTrue(credit.getAccountId()))
                .thenReturn(Optional.of(buildChartOfAccount(credit.getAccountId(), credit.getAccountNumber())));
        when(coaRepository.findByAccountIdAndIsActiveTrue(debit.getAccountId()))
                .thenReturn(Optional.of(buildChartOfAccount(debit.getAccountId(), debit.getAccountNumber())));
        when(entryRepository.findLatestRunningBalance(any())).thenReturn(Optional.of(new BigDecimal("5000.00")));

        commandService.postReversal(originalPostingId, "customer dispute", "trace-rev");

        assertThat(original.getStatus()).isEqualTo(
                com.nexus.ledger.domain.model.enums.PostingStatus.REVERSED);
        verify(outboxRepository).save(argThat(e -> e.getEventType().equals("LedgerReversed")));
    }

    @Test
    @DisplayName("postReversal: rejects reversing an already-reversed posting")
    void postReversal_alreadyReversed_throws() {
        var originalPostingId = UUID.randomUUID();
        var original = com.nexus.ledger.domain.model.Posting.builder()
                .postingId(originalPostingId)
                .postingType(PostingType.TRANSFER)
                .status(com.nexus.ledger.domain.model.enums.PostingStatus.REVERSED)
                .entryCount(2)
                .totalDebit(new BigDecimal("500.00"))
                .totalCredit(new BigDecimal("500.00"))
                .isBalanced(true)
                .currency("MXN")
                .build();
        when(postingRepository.findById(originalPostingId)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> commandService.postReversal(originalPostingId, "dup reversal", "trace-rev"))
                .isInstanceOf(IllegalStateException.class);
    }

    private ChartOfAccount buildChartOfAccount(UUID accountId, String accountNumber) {
        return ChartOfAccount.builder()
                .accountId(accountId)
                .accountNumber(accountNumber)
                .accountName("Test Account")
                .accountType("ASSET")
                .accountSubtype("USER_CHECKING")
                .normalBalance("DEBIT")
                .currency("MXN")
                .isUserAccount(true)
                .isActive(true)
                .openingBalance(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("Ledger entry checksum is verifiable")
    void ledgerEntry_checksum_isComputableAndVerifiable() throws Exception {
        var entry = buildDebitEntry(UUID.randomUUID());

        // prePersist() is a package-private @PrePersist JPA lifecycle
        // callback (different package than this test, normally invoked by
        // the persistence provider, not application/test code) - can't
        // call it directly. computeChecksum() is the public API this test
        // actually needs; setting the private checksum field via
        // reflection mirrors what prePersist() itself does internally.
        String checksum = entry.computeChecksum();
        var field = entry.getClass().getDeclaredField("checksum");
        field.setAccessible(true);
        field.set(entry, checksum);

        assertThat(entry.getChecksum()).isNotNull();
        assertThat(entry.getChecksum()).hasSize(64); // SHA-256 hex
        assertThat(entry.isChecksumValid()).isTrue();
    }

    private com.nexus.ledger.domain.model.Posting buildPosting(
            UUID txnId) {
        return com.nexus.ledger.domain.model.Posting.builder()
                .postingId(UUID.randomUUID())
                .transactionId(txnId)
                .postingType(PostingType.TRANSFER)
                .status(com.nexus.ledger.domain.model.enums
                        .PostingStatus.POSTED)
                .entryCount(2)
                .totalDebit(new BigDecimal("500.00"))
                .totalCredit(new BigDecimal("500.00"))
                .isBalanced(true)
                .currency("MXN")
                .build();
    }

    private com.nexus.ledger.domain.model.LedgerEntry buildDebitEntry(
            UUID postingId) {
        return com.nexus.ledger.domain.model.LedgerEntry.builder()
                .entryId(UUID.randomUUID())
                .postingId(postingId)
                .accountId(UUID.randomUUID())
                .accountNumber("USR-CHK-00001")
                .accountType("USER_CHECKING")
                .entryType(com.nexus.ledger.domain.model.enums
                        .EntryType.DEBIT)
                .amount(new BigDecimal("500.00"))
                .currency("MXN")
                .runningBalance(new BigDecimal("4500.00"))
                .category("TRANSFER")
                .fiscalYear(2025)
                .fiscalMonth(5)
                .fiscalQuarter(2)
                .build();
    }

    private com.nexus.ledger.domain.model.LedgerEntry buildCreditEntry(
            UUID postingId) {
        return com.nexus.ledger.domain.model.LedgerEntry.builder()
                .entryId(UUID.randomUUID())
                .postingId(postingId)
                .accountId(UUID.randomUUID())
                .accountNumber("USR-SAV-00001")
                .accountType("USER_SAVINGS")
                .entryType(com.nexus.ledger.domain.model.enums
                        .EntryType.CREDIT)
                .amount(new BigDecimal("500.00"))
                .currency("MXN")
                .runningBalance(new BigDecimal("2500.00"))
                .category("TRANSFER")
                .fiscalYear(2025)
                .fiscalMonth(5)
                .fiscalQuarter(2)
                .build();
    }

    private PostLedgerCommand buildCommand(UUID txnId) {
        return PostLedgerCommand.builder()
                .transactionId(txnId)
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .amount(new BigDecimal("500.00"))
                .currency("MXN")
                .postingType(PostingType.TRANSFER)
                .description("Test transfer")
                .sagaId(UUID.randomUUID().toString())
                .traceId("trace-001")
                .build();
    }
}
