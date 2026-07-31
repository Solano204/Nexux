package com.nexus.transaction.unit;

import com.nexus.transaction.domain.exception.InvalidTransactionStateException;
import com.nexus.transaction.domain.model.Transaction;
import com.nexus.transaction.domain.model.enums.TransactionStatus;
import com.nexus.transaction.domain.model.enums.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void balanceFirstHappyPathReachesCompleted() {
        Transaction txn = buildTransaction(TransactionStatus.INITIATED);

        txn.markBalanceReserving();
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.BALANCE_RESERVING);

        txn.markBalanceReserved();
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.BALANCE_RESERVED);
        assertThat(txn.getBalanceReservedAt()).isNotNull();

        txn.markFraudCleared(new BigDecimal("5.00"), List.of());
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FRAUD_CLEARED);
        assertThat(txn.getFraudDecision()).isEqualTo("CLEARED");

        txn.markLedgerPosting();
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.LEDGER_POSTING);

        UUID ledgerEntryId = UUID.randomUUID();
        txn.markLedgerPosted(ledgerEntryId);
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.LEDGER_POSTED);
        assertThat(txn.getLedgerEntryId()).isEqualTo(ledgerEntryId);

        txn.markCompleted();
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(txn.getCompletedAt()).isNotNull();
    }

    @Test
    void depositPathSkipsBalanceAndFraudSteps() {
        Transaction txn = buildTransaction(TransactionStatus.INITIATED);

        txn.markLedgerPosting();
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.LEDGER_POSTING);

        txn.markLedgerPosted(UUID.randomUUID());
        txn.markCompleted();
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void fraudRejectionSetsFailureDetails() {
        Transaction txn = buildTransaction(TransactionStatus.BALANCE_RESERVED);

        txn.markFraudRejected(new BigDecimal("92.50"), List.of("VELOCITY_ANOMALY"));

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FRAUD_REJECTED);
        assertThat(txn.getFraudDecision()).isEqualTo("REJECTED");
        assertThat(txn.getFailedAt()).isNotNull();
        assertThat(txn.getFailureReason()).contains("FRAUD_REJECTED");
    }

    @Test
    void reserveFailureIsRecorded() {
        Transaction txn = buildTransaction(TransactionStatus.BALANCE_RESERVING);

        txn.markReserveFailed("INSUFFICIENT_FUNDS");

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.RESERVE_FAILED);
        assertThat(txn.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void ledgerFailureTransitionsToReversingEventually() {
        Transaction txn = buildTransaction(TransactionStatus.LEDGER_POSTING);

        txn.markLedgerFailed("LEDGER_DB_TIMEOUT");
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.LEDGER_FAILED);

        txn.markReversed();
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.REVERSED);
    }

    @Test
    void markFailedIsNoOpOnTerminalStatus() {
        Transaction txn = buildTransaction(TransactionStatus.COMPLETED);

        txn.markFailed("SHOULD_NOT_APPLY");

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(txn.getFailureReason()).isNull();
    }

    @Test
    void markFailedAppliesFromNonTerminalStatus() {
        Transaction txn = buildTransaction(TransactionStatus.BALANCE_RESERVING);

        txn.markFailed("MANUAL_CANCEL");

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(txn.getFailureReason()).isEqualTo("MANUAL_CANCEL");
    }

    @Test
    void rejectsInvalidTransitionSkippingSteps() {
        Transaction txn = buildTransaction(TransactionStatus.INITIATED);

        assertThatThrownBy(() -> txn.markLedgerPosted(UUID.randomUUID()))
                .isInstanceOf(InvalidTransactionStateException.class);
    }

    @Test
    void rejectsTransitionFromTerminalCompletedState() {
        Transaction txn = buildTransaction(TransactionStatus.COMPLETED);

        assertThatThrownBy(txn::markBalanceReserving)
                .isInstanceOf(InvalidTransactionStateException.class);
    }

    @Test
    void isTerminalStatusIdentifiesAllTerminalStates() {
        assertThat(Transaction.isTerminalStatus(TransactionStatus.COMPLETED)).isTrue();
        assertThat(Transaction.isTerminalStatus(TransactionStatus.FAILED)).isTrue();
        assertThat(Transaction.isTerminalStatus(TransactionStatus.REVERSED)).isTrue();
        assertThat(Transaction.isTerminalStatus(TransactionStatus.CANCELLED)).isTrue();
        assertThat(Transaction.isTerminalStatus(TransactionStatus.FRAUD_REJECTED)).isTrue();
        assertThat(Transaction.isTerminalStatus(TransactionStatus.INITIATED)).isFalse();
        assertThat(Transaction.isTerminalStatus(TransactionStatus.LEDGER_POSTING)).isFalse();
    }
}
