package com.nexus.transaction.unit;

import com.nexus.transaction.domain.exception.InvalidTransactionStateException;
import com.nexus.transaction.domain.model.Transaction;
import com.nexus.transaction.domain.model.enums.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Tag("unit")
class TransactionStateMachineTest {

    private Transaction buildTransaction(TransactionStatus status) {
        return Transaction.builder()
                .transactionId(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .userId(UUID.randomUUID())
                .sourceAccountId(UUID.randomUUID())
                .amount(new BigDecimal("500.00"))
                .currency("MXN")
                .transactionType(TransactionType.INTERNAL_TRANSFER)
                .status(status)
                .feeAmount(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("Happy path: INITIATED → COMPLETED")
    void happyPath_fullLifecycle() {
        Transaction txn = buildTransaction(TransactionStatus.INITIATED);

        txn.markFraudChecking();
        assertThat(txn.getStatus()).isEqualTo(
                TransactionStatus.FRAUD_CHECKING);

        txn.markFraudCleared(new BigDecimal("0.05"), List.of());
        assertThat(txn.getStatus()).isEqualTo(
                TransactionStatus.FRAUD_CLEARED);

        txn.markBalanceReserving();
        txn.markBalanceReserved();
        txn.markLedgerPosting();
        txn.markLedgerPosted(UUID.randomUUID());
        txn.markCompleted();

        assertThat(txn.getStatus()).isEqualTo(
                TransactionStatus.COMPLETED);
        assertThat(txn.getCompletedAt()).isNotNull();
        assertThat(txn.getLedgerEntryId()).isNotNull();
    }

    @Test
    @DisplayName("Fraud rejection path: INITIATED → FRAUD_REJECTED → FAILED")
    void fraudRejection_terminatesCorrectly() {
        Transaction txn = buildTransaction(TransactionStatus.INITIATED);

        txn.markFraudChecking();
        txn.markFraudRejected(
                new BigDecimal("0.95"),
                List.of("HIGH_VELOCITY", "UNUSUAL_AMOUNT"));

        assertThat(txn.getStatus()).isEqualTo(
                TransactionStatus.FRAUD_REJECTED);
        assertThat(txn.getFraudDecision()).isEqualTo("REJECTED");
        assertThat(txn.getFraudReasons())
                .contains("HIGH_VELOCITY", "UNUSUAL_AMOUNT");
        assertThat(txn.getFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("Invalid transition throws exception")
    void invalidTransition_throwsException() {
        Transaction txn = buildTransaction(TransactionStatus.INITIATED);

        // Cannot skip FRAUD_CHECKING
        assertThatThrownBy(() ->
                txn.markBalanceReserving()
        ).isInstanceOf(InvalidTransactionStateException.class)
                .hasMessageContaining("INITIATED")
                .hasMessageContaining("BALANCE_RESERVING");
    }

    @Test
    @DisplayName("Terminal states cannot transition")
    void terminalStates_cannotTransition() {
        for (TransactionStatus terminal : List.of(
                TransactionStatus.COMPLETED,
                TransactionStatus.FAILED,
                TransactionStatus.REVERSED,
                TransactionStatus.CANCELLED)) {

            Transaction txn = buildTransaction(terminal);
            assertThatThrownBy(txn::markFraudChecking)
                    .isInstanceOf(InvalidTransactionStateException.class);
        }
    }

    @Test
    @DisplayName("markFailed is idempotent on terminal states")
    void markFailed_onTerminalState_isIdempotent() {
        Transaction txn = buildTransaction(TransactionStatus.COMPLETED);
        // Should not throw — markFailed checks isTerminalStatus
        assertThatCode(() ->
                txn.markFailed("some reason")
        ).doesNotThrowAnyException();
        // Status unchanged
        assertThat(txn.getStatus()).isEqualTo(
                TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("Ledger failure path triggers reversal")
    void ledgerFailure_triggersReversal() {
        Transaction txn = buildTransaction(TransactionStatus.INITIATED);
        txn.markFraudChecking();
        txn.markFraudCleared(new BigDecimal("0.1"), List.of());
        txn.markBalanceReserving();
        txn.markBalanceReserved();
        txn.markLedgerPosting();
        txn.markLedgerFailed("LEDGER_UNAVAILABLE");

        assertThat(txn.getStatus()).isEqualTo(
                TransactionStatus.LEDGER_FAILED);
        assertThat(txn.getFailureReason())
                .isEqualTo("LEDGER_UNAVAILABLE");

        // After ledger failure, reversal should be possible
        txn.markReversed();
        assertThat(txn.getStatus()).isEqualTo(
                TransactionStatus.REVERSED);
    }
}