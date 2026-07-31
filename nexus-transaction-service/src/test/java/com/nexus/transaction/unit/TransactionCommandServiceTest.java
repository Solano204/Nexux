package com.nexus.transaction.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.transaction.application.command.TransactionCommandService;
import com.nexus.transaction.domain.model.Transaction;
import com.nexus.transaction.domain.model.enums.TransactionStatus;
import com.nexus.transaction.domain.model.enums.TransactionType;
import com.nexus.transaction.infrastructure.elasticsearch.ElasticsearchIndexingService;
import com.nexus.transaction.infrastructure.kafka.TransactionEventProducer;
import com.nexus.transaction.infrastructure.persistence.OutboxRepository;
import com.nexus.transaction.infrastructure.persistence.TransactionRepository;
import com.nexus.transaction.web.dto.request.InitiateTransactionRequest;
import com.nexus.transaction.web.dto.response.TransactionResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionCommandServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private TransactionEventProducer eventProducer;
    @Mock private ElasticsearchIndexingService searchIndexer;
    @Mock private Tracer tracer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private TransactionCommandService service;
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TransactionCommandService(transactionRepository, outboxRepository, eventProducer,
                searchIndexer, objectMapper, ObservationRegistry.NOOP, tracer, meterRegistry);
        // Mocked save() bypasses Hibernate entirely, so Transaction's real
        // @PrePersist hook (which sets initiatedAt) never runs here — simulate
        // that one effect so response-building code that reads initiatedAt
        // doesn't NPE against a freshly-built, unsaved Transaction.
        lenient().when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getInitiatedAt() == null) t.setInitiatedAt(Instant.now());
            return t;
        });
    }

    private InitiateTransactionRequest transferRequest() {
        return new InitiateTransactionRequest("idem-key-123", UUID.randomUUID(), UUID.randomUUID(), null,
                null, new BigDecimal("500.00"), "MXN", TransactionType.INTERNAL_TRANSFER, null,
                "rent", null, null, null);
    }

    private InitiateTransactionRequest externalTransferRequest() {
        return new InitiateTransactionRequest("idem-key-456", UUID.randomUUID(), null, "ACC-EXT-1",
                null, new BigDecimal("1000.00"), "MXN", TransactionType.EXTERNAL_TRANSFER, null,
                "external", null, null, null);
    }

    @Nested
    class InitiateTransaction {

        @Test
        void createsNewTransactionWithZeroFeeForInternalTransfer() {
            when(transactionRepository.findByUserIdAndIdempotencyKey(any(), anyString())).thenReturn(Optional.empty());

            TransactionResponse response = service.initiateTransaction(
                    transferRequest(), USER_ID, "1.2.3.4", "device-1", "trace-1");

            assertThat(response.status()).isEqualTo("INITIATED");
            assertThat(response.amount()).isEqualByComparingTo("500.00");
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("TransactionInitiated")));
            verify(searchIndexer).indexAsync(any());
            assertThat(meterRegistry.counter("transaction.initiated.total").count()).isEqualTo(1.0);
        }

        @Test
        void calculatesOnePointFivePercentFeeForExternalTransfer() {
            when(transactionRepository.findByUserIdAndIdempotencyKey(any(), anyString())).thenReturn(Optional.empty());

            service.initiateTransaction(externalTransferRequest(), USER_ID, "1.2.3.4", "device-1", "trace-1");

            var captor = org.mockito.ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getFeeAmount()).isEqualByComparingTo("15.0000");
        }

        @Test
        void returnsExistingTransactionForDuplicateIdempotencyKey() {
            Transaction existing = Transaction.builder()
                    .transactionId(UUID.randomUUID()).userId(USER_ID)
                    .amount(new BigDecimal("500.00")).currency("MXN")
                    .transactionType(TransactionType.INTERNAL_TRANSFER)
                    .status(TransactionStatus.COMPLETED)
                    .initiatedAt(java.time.Instant.now())
                    .build();
            when(transactionRepository.findByUserIdAndIdempotencyKey(USER_ID, "idem-key-123"))
                    .thenReturn(Optional.of(existing));

            TransactionResponse response = service.initiateTransaction(
                    transferRequest(), USER_ID, "1.2.3.4", "device-1", "trace-1");

            assertThat(response.transactionId()).isEqualTo(existing.getTransactionId().toString());
            verify(transactionRepository, never()).save(any());
            verify(outboxRepository, never()).save(any());
        }

        @Test
        void defaultsCurrencyToMxnWhenNull() {
            when(transactionRepository.findByUserIdAndIdempotencyKey(any(), anyString())).thenReturn(Optional.empty());
            InitiateTransactionRequest req = new InitiateTransactionRequest("idem-1", UUID.randomUUID(),
                    UUID.randomUUID(), null, null, new BigDecimal("10.00"), null,
                    TransactionType.INTERNAL_TRANSFER, null, null, null, null, null);

            TransactionResponse response = service.initiateTransaction(req, USER_ID, "1.2.3.4", "device-1", "trace-1");

            assertThat(response.currency()).isEqualTo("MXN");
        }
    }

    @Nested
    class ProcessFraudResult {

        private Transaction reservedTransaction() {
            return Transaction.builder()
                    .transactionId(UUID.randomUUID()).userId(USER_ID)
                    .sourceAccountId(UUID.randomUUID())
                    .amount(new BigDecimal("500.00")).currency("MXN")
                    .transactionType(TransactionType.INTERNAL_TRANSFER)
                    .status(TransactionStatus.BALANCE_RESERVED)
                    .build();
        }

        @Test
        void clearsTransactionOnFraudPass() {
            Transaction txn = reservedTransaction();
            when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));

            service.processFraudResult(txn.getTransactionId(), UUID.randomUUID(), true,
                    new BigDecimal("2.5"), List.of(), "trace-1");

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FRAUD_CLEARED);
            verify(outboxRepository, never()).save(any());
        }

        @Test
        void rejectsTransactionOnFraudFail() {
            Transaction txn = reservedTransaction();
            when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));

            service.processFraudResult(txn.getTransactionId(), UUID.randomUUID(), false,
                    new BigDecimal("95.0"), List.of("VELOCITY"), "trace-1");

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FRAUD_REJECTED);
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("TransactionFraudRejected")));
            assertThat(meterRegistry.counter("transaction.fraud.rejected.total").count()).isEqualTo(1.0);
        }

        @Test
        void ignoresResultForAlreadyCompletedTransaction() {
            Transaction txn = reservedTransaction();
            txn.setStatus(TransactionStatus.COMPLETED);
            when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));

            service.processFraudResult(txn.getTransactionId(), UUID.randomUUID(), true,
                    new BigDecimal("2.5"), List.of(), "trace-1");

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            verify(transactionRepository, never()).save(any());
        }

        @Test
        void throwsWhenTransactionNotFound() {
            UUID missingId = UUID.randomUUID();
            when(transactionRepository.findById(missingId)).thenReturn(Optional.empty());

            org.junit.jupiter.api.Assertions.assertThrows(
                    com.nexus.transaction.domain.exception.TransactionNotFoundException.class,
                    () -> service.processFraudResult(missingId, UUID.randomUUID(), true,
                            BigDecimal.ZERO, List.of(), "trace-1"));
        }
    }

    @Nested
    class ProcessBalanceResult {

        private Transaction initiatedTransaction() {
            return Transaction.builder()
                    .transactionId(UUID.randomUUID()).userId(USER_ID)
                    .sourceAccountId(UUID.randomUUID())
                    .amount(new BigDecimal("500.00")).currency("MXN")
                    .transactionType(TransactionType.INTERNAL_TRANSFER)
                    .status(TransactionStatus.INITIATED)
                    .build();
        }

        @Test
        void reservesBalanceOnSuccess() {
            Transaction txn = initiatedTransaction();
            when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));
            when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            service.processBalanceResult(txn.getTransactionId(), UUID.randomUUID(), true, null, "trace-1");

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.BALANCE_RESERVED);
        }

        @Test
        void failsTransactionWhenInsufficientFunds() {
            Transaction txn = initiatedTransaction();
            when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));

            service.processBalanceResult(txn.getTransactionId(), UUID.randomUUID(), false,
                    "INSUFFICIENT_FUNDS", "trace-1");

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FAILED);
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("TransactionFailed")));
            assertThat(meterRegistry.counter("transaction.failed.total").count()).isEqualTo(1.0);
        }
    }

    @Nested
    class ProcessLedgerResult {

        private Transaction ledgerPostedReadyTransaction() {
            return Transaction.builder()
                    .transactionId(UUID.randomUUID()).userId(USER_ID)
                    .sourceAccountId(UUID.randomUUID())
                    .amount(new BigDecimal("500.00")).currency("MXN")
                    .transactionType(TransactionType.INTERNAL_TRANSFER)
                    .status(TransactionStatus.LEDGER_POSTING)
                    .build();
        }

        @Test
        void completesTransactionOnLedgerSuccess() {
            Transaction txn = ledgerPostedReadyTransaction();
            when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));
            when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            service.processLedgerResult(txn.getTransactionId(), UUID.randomUUID(), true,
                    UUID.randomUUID(), null, "trace-1");

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            verify(outboxRepository).save(argThat(e ->
                    e.getAggregateType().equals("transactions.completed") && e.getEventType().equals("TransactionCompleted")));
            verify(outboxRepository).save(argThat(e ->
                    e.getAggregateType().equals("user.behavior.aggregated") && e.getEventType().equals("TransactionCompleted")));
            assertThat(meterRegistry.counter("transaction.completed.total").count()).isEqualTo(1.0);
        }

        @Test
        void releasesBalanceAndFailsOnLedgerFailure() {
            Transaction txn = ledgerPostedReadyTransaction();
            when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));

            service.processLedgerResult(txn.getTransactionId(), UUID.randomUUID(), false,
                    null, "LEDGER_DB_ERROR", "trace-1");

            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.LEDGER_FAILED);
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("BalanceReleaseRequested")));
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("TransactionFailed")));
        }

        @Test
        void isIdempotentWhenAlreadyCompleted() {
            Transaction txn = ledgerPostedReadyTransaction();
            txn.setStatus(TransactionStatus.COMPLETED);
            when(transactionRepository.findById(txn.getTransactionId())).thenReturn(Optional.of(txn));

            service.processLedgerResult(txn.getTransactionId(), UUID.randomUUID(), true,
                    UUID.randomUUID(), null, "trace-1");

            verify(transactionRepository, never()).save(any());
            verify(outboxRepository, never()).save(any());
        }
    }
}
