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
    @Mock AccountLedgerSummaryRepository summaryRepository;
    @Mock PostingDocumentRepository postingDocRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock Tracer tracer;

    LedgerCommandService commandService;

    @BeforeEach
    void setUp() {
        commandService = new LedgerCommandService(
                entryRepository, postingRepository, coaRepository,
                outboxRepository, summaryRepository, postingDocRepository,
                kafkaTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                ObservationRegistry.NOOP, tracer,
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
    @DisplayName("Ledger entry checksum is verifiable")
    void ledgerEntry_checksum_isComputableAndVerifiable() {
        var entry = buildDebitEntry(UUID.randomUUID());
        // Force checksum computation
        entry.prePersist();

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