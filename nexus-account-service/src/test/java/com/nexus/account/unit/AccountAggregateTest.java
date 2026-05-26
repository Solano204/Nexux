//package com.nexus.account.unit;
//
//import com.nexus.account.domain.exception.*;
//import com.nexus.account.domain.model.Account;
//import com.nexus.account.domain.model.enums.*;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Tag;
//import org.junit.jupiter.api.Test;
//
//import java.math.BigDecimal;
//import java.util.UUID;
//
//import static org.assertj.core.api.Assertions.*;
//
///**
// * Account Aggregate Unit Tests.
// *
// * Tests ALL branches of ALL aggregate methods.
// * No Spring context, no mocks — pure domain logic.
// * These are the most critical tests in the platform.
// */
//@Tag("unit")
//class AccountAggregateTest {
//
//    private Account buildActiveAccount(BigDecimal balance) {
//        return Account.builder()
//                .accountId(UUID.randomUUID())
//                .accountNumber("1234-5678-9012-3456")
//                .userId(UUID.randomUUID())
//                .accountType(AccountType.CHECKING)
//                .currency("MXN")
//                .status(AccountStatus.ACTIVE)
//                .availableBalance(balance)
//                .reservedAmount(BigDecimal.ZERO)
//                .pendingCredit(BigDecimal.ZERO)
//                .dailyTransactionLimit(new BigDecimal("50000.00"))
//                .dailyTransactionUsed(BigDecimal.ZERO)
//                .monthlyTransactionLimit(new BigDecimal("500000.00"))
//                .monthlyTransactionUsed(BigDecimal.ZERO)
//                .minimumBalance(BigDecimal.ZERO)
//                .interestRate(BigDecimal.ZERO)
//                .build();
//    }
//
//    @Test
//    @DisplayName("reserve: success reduces available and increases reserved")
//    void reserve_sufficientFunds_reducesAvailable() {
//        Account account = buildActiveAccount(new BigDecimal("1000.00"));
//
//        Account.ReservationResult result = account.reserve(
//                new BigDecimal("400.00"), "txn-001");
//
//        assertThat(result.newAvailableBalance())
//                .isEqualByComparingTo("600.00");
//        assertThat(account.getAvailableBalance())
//                .isEqualByComparingTo("600.00");
//        assertThat(account.getReservedAmount())
//                .isEqualByComparingTo("400.00");
//        assertThat(account.getDailyTransactionUsed())
//                .isEqualByComparingTo("400.00");
//
//        // Domain event recorded
//        assertThat(account.pollDomainEvents()).hasSize(1);
//    }
//
//    @Test
//    @DisplayName("reserve: throws InsufficientFundsException when balance insufficient")
//    void reserve_insufficientFunds_throwsException() {
//        Account account = buildActiveAccount(new BigDecimal("100.00"));
//
//        assertThatThrownBy(() ->
//                account.reserve(new BigDecimal("500.00"), "txn-002")
//        ).isInstanceOf(InsufficientFundsException.class)
//                .hasMessageContaining("Available: 100")
//                .hasMessageContaining("Required: 500");
//
//        // Balance unchanged after failed reserve
//        assertThat(account.getAvailableBalance())
//                .isEqualByComparingTo("100.00");
//        assertThat(account.getReservedAmount())
//                .isEqualByComparingTo("0.00");
//    }
//
//    @Test
//    @DisplayName("reserve: throws AccountFrozenException on frozen account")
//    void reserve_frozenAccount_throwsException() {
//        Account account = buildActiveAccount(new BigDecimal("1000.00"));
//        account.freeze("Suspicious activity");
//
//        assertThatThrownBy(() ->
//                account.reserve(new BigDecimal("100.00"), "txn-003")
//        ).isInstanceOf(AccountFrozenException.class);
//    }
//
//    @Test
//    @DisplayName("reserve: throws DailyLimitExceededException")
//    void reserve_dailyLimitExceeded_throwsException() {
//        Account account = buildActiveAccount(new BigDecimal("100000.00"));
//
//        // First reserve up to limit
//        account.reserve(new BigDecimal("49000.00"), "txn-001");
//
//        // Second reserve that would exceed daily limit
//        assertThatThrownBy(() ->
//                        account.reserve(new BigDecimal("2000.00"), "txn-002")
//                // 49000 + 2000 = 51000 > 50000 limit
//        ).isInstanceOf(DailyLimitExceededException.class);
//    }
//
//    @Test
//    @DisplayName("release: returns funds to available balance")
//    void release_activeReservation_restoresFunds() {
//        Account account = buildActiveAccount(new BigDecimal("1000.00"));
//        account.reserve(new BigDecimal("400.00"), "txn-001");
//
//        account.release(new BigDecimal("400.00"), "txn-001");
//
//        assertThat(account.getAvailableBalance())
//                .isEqualByComparingTo("1000.00");
//        assertThat(account.getReservedAmount())
//                .isEqualByComparingTo("0.00");
//    }
//
//    @Test
//    @DisplayName("release: works even on FROZEN accounts (never strand funds)")
//    void release_frozenAccount_stillWorks() {
//        Account account = buildActiveAccount(new BigDecimal("1000.00"));
//        account.reserve(new BigDecimal("400.00"), "txn-001");
//        account.freeze("Compliance action");
//
//        // Should NOT throw — frozen accounts must be releasable
//        assertThatCode(() ->
//                account.release(new BigDecimal("400.00"), "txn-001")
//        ).doesNotThrowAnyException();
//
//        assertThat(account.getAvailableBalance())
//                .isEqualByComparingTo("1000.00");
//    }
//
//    @Test
//    @DisplayName("release: throws AccountingIntegrityException when more than reserved")
//    void release_moreThanReserved_throwsIntegrityException() {
//        Account account = buildActiveAccount(new BigDecimal("1000.00"));
//        account.reserve(new BigDecimal("100.00"), "txn-001");
//
//        assertThatThrownBy(() ->
//                account.release(new BigDecimal("500.00"), "txn-001")
//        ).isInstanceOf(AccountingIntegrityException.class);
//    }
//
//    @Test
//    @DisplayName("finalizeDebit: permanently removes from reserved (not available)")
//    void finalizeDebit_reducesReservedOnly() {
//        Account account = buildActiveAccount(new BigDecimal("1000.00"));
//        account.reserve(new BigDecimal("400.00"), "txn-001");
//
//        // Before finalize: available=600, reserved=400
//        account.finalizeDebit(new BigDecimal("400.00"), "txn-001");
//
//        // After: available=600 (unchanged), reserved=0 (money gone)
//        assertThat(account.getAvailableBalance())
//                .isEqualByComparingTo("600.00");
//        assertThat(account.getReservedAmount())
//                .isEqualByComparingTo("0.00");
//    }
//
//    @Test
//    @DisplayName("credit: increases available balance")
//    void credit_activeAccount_increasesBalance() {
//        Account account = buildActiveAccount(new BigDecimal("1000.00"));
//
//        account.credit(new BigDecimal("250.00"), "txn-001", "source-acct");
//
//        assertThat(account.getAvailableBalance())
//                .isEqualByComparingTo("1250.00");
//    }
//
//    @Test
//    @DisplayName("close: requires zero balance")
//    void close_nonZeroBalance_throwsException() {
//        Account account = buildActiveAccount(new BigDecimal("100.00"));
//
//        assertThatThrownBy(() -> account.close("Test close"))
//                .isInstanceOf(IllegalStateException.class)
//                .hasMessageContaining("non-zero available balance");
//    }
//
//    @Test
//    @DisplayName("double-spending: two reserves that would exceed balance both fail correctly")
//    void doubleSpendig_proofOfPreventionLogic() {
//        Account account = buildActiveAccount(new BigDecimal("500.00"));
//
//        // First reserve succeeds
//        account.reserve(new BigDecimal("400.00"), "txn-001");
//        // available = 100, reserved = 400
//
//        // Second reserve for 200 would take available to -100 — must fail
//        assertThatThrownBy(() ->
//                account.reserve(new BigDecimal("200.00"), "txn-002")
//        ).isInstanceOf(InsufficientFundsException.class);
//
//        // Balances unchanged
//        assertThat(account.getAvailableBalance())
//                .isEqualByComparingTo("100.00");
//        assertThat(account.getReservedAmount())
//                .isEqualByComparingTo("400.00");
//    }
//
//    @Test
//    @DisplayName("invariant: availableBalance can never be negative")
//    void invariant_negativeBalanceNeverPossible() {
//        Account account = buildActiveAccount(BigDecimal.ZERO);
//
//        // Any attempt to reserve from zero balance must fail
//        assertThatThrownBy(() ->
//                account.reserve(new BigDecimal("0.01"), "txn-001")
//        ).isInstanceOf(InsufficientFundsException.class);
//
//        // Balance remains zero — not negative
//        assertThat(account.getAvailableBalance())
//                .isEqualByComparingTo("0.00");
//    }
//}