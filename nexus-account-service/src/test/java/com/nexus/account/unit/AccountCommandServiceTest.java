package com.nexus.account.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.account.application.command.AccountCommandService;
import com.nexus.account.domain.model.Account;
import com.nexus.account.domain.model.BalanceReservation;
import com.nexus.account.domain.model.enums.AccountStatus;
import com.nexus.account.domain.model.enums.AccountType;
import com.nexus.account.domain.model.enums.ReservationStatus;
import com.nexus.account.infrastructure.mongodb.AccountAnalyticsRepository;
import com.nexus.account.infrastructure.persistence.*;
import com.nexus.account.infrastructure.redis.BalanceCacheRepository;
import com.nexus.account.infrastructure.redis.ReservationLockRepository;
import com.nexus.account.integration.AccountIntegrationEventMapper;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountCommandServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private BalanceReservationRepository reservationRepository;
    @Mock private AccountEventRepository accountEventRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private AccountAnalyticsRepository analyticsRepository;
    @Mock private BalanceCacheRepository balanceCacheRepository;
    @Mock private ReservationLockRepository lockRepository;
    @Mock private Tracer tracer;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AccountIntegrationEventMapper eventMapper = new AccountIntegrationEventMapper();

    private AccountCommandService service;
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID TXN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AccountCommandService(accountRepository, reservationRepository, accountEventRepository,
                outboxRepository, analyticsRepository, balanceCacheRepository, lockRepository, objectMapper,
                ObservationRegistry.NOOP, tracer, meterRegistry, eventMapper);

        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reservationRepository.save(any(BalanceReservation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Account activeAccount(BigDecimal available, BigDecimal reserved) {
        return Account.builder()
                .accountId(ACCOUNT_ID)
                .accountNumber("1111-2222-3333-4444")
                .userId(UUID.randomUUID())
                .accountType(AccountType.CHECKING)
                .currency("MXN")
                .status(AccountStatus.ACTIVE)
                .availableBalance(available)
                .reservedAmount(reserved)
                .pendingCredit(BigDecimal.ZERO)
                .dailyTransactionLimit(new BigDecimal("50000.00"))
                .dailyTransactionUsed(BigDecimal.ZERO)
                .monthlyTransactionLimit(new BigDecimal("1000000.00"))
                .monthlyTransactionUsed(BigDecimal.ZERO)
                .minimumBalance(BigDecimal.ZERO)
                .interestRate(BigDecimal.ZERO)
                .build();
    }

    @Nested
    class ReserveBalance {

        @Test
        void succeedsAndWritesReservationAndOutbox() {
            when(reservationRepository.findByAccountIdAndTransactionId(ACCOUNT_ID, TXN_ID))
                    .thenReturn(Optional.empty());
            when(lockRepository.tryAcquireLock(eq(ACCOUNT_ID), anyString())).thenReturn(true);
            when(accountRepository.findWithLockById(ACCOUNT_ID))
                    .thenReturn(Optional.of(activeAccount(new BigDecimal("1000.00"), BigDecimal.ZERO)));

            var result = service.reserveBalance(ACCOUNT_ID, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(result.success()).isTrue();
            assertThat(result.newAvailableBalance()).isEqualByComparingTo("700.00");
            verify(reservationRepository).save(argThat(r -> r.getStatus() == ReservationStatus.ACTIVE));
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("BalanceReserved")));
            verify(lockRepository).releaseLock(eq(ACCOUNT_ID), anyString());
            verify(balanceCacheRepository).invalidate(ACCOUNT_ID);
        }

        @Test
        void returnsIdempotentReplayForExistingReservation() {
            BalanceReservation existing = BalanceReservation.builder()
                    .reservationId(UUID.randomUUID()).accountId(ACCOUNT_ID).transactionId(TXN_ID)
                    .reservedAmount(new BigDecimal("300.00")).status(ReservationStatus.ACTIVE).build();
            when(reservationRepository.findByAccountIdAndTransactionId(ACCOUNT_ID, TXN_ID))
                    .thenReturn(Optional.of(existing));

            var result = service.reserveBalance(ACCOUNT_ID, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(result.success()).isTrue();
            assertThat(result.idempotentReplay()).isTrue();
            verifyNoInteractions(lockRepository);
        }

        @Test
        void failsWithConcurrentReservationWhenLockNotAcquired() {
            when(reservationRepository.findByAccountIdAndTransactionId(ACCOUNT_ID, TXN_ID))
                    .thenReturn(Optional.empty());
            when(lockRepository.tryAcquireLock(eq(ACCOUNT_ID), anyString())).thenReturn(false);

            var result = service.reserveBalance(ACCOUNT_ID, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("CONCURRENT_RESERVATION_IN_PROGRESS");
            verify(accountRepository, never()).findWithLockById(any());
        }

        @Test
        void failsWithInsufficientFundsAndReleasesLock() {
            when(reservationRepository.findByAccountIdAndTransactionId(ACCOUNT_ID, TXN_ID))
                    .thenReturn(Optional.empty());
            when(lockRepository.tryAcquireLock(eq(ACCOUNT_ID), anyString())).thenReturn(true);
            when(accountRepository.findWithLockById(ACCOUNT_ID))
                    .thenReturn(Optional.of(activeAccount(new BigDecimal("100.00"), BigDecimal.ZERO)));

            var result = service.reserveBalance(ACCOUNT_ID, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("INSUFFICIENT_FUNDS");
            verify(lockRepository).releaseLock(eq(ACCOUNT_ID), anyString());
            verify(reservationRepository, never()).save(any());
        }

        @Test
        void failsWithAccountFrozen() {
            Account frozen = activeAccount(new BigDecimal("1000.00"), BigDecimal.ZERO);
            frozen.freeze("compliance hold");
            when(reservationRepository.findByAccountIdAndTransactionId(ACCOUNT_ID, TXN_ID))
                    .thenReturn(Optional.empty());
            when(lockRepository.tryAcquireLock(eq(ACCOUNT_ID), anyString())).thenReturn(true);
            when(accountRepository.findWithLockById(ACCOUNT_ID)).thenReturn(Optional.of(frozen));

            var result = service.reserveBalance(ACCOUNT_ID, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("ACCOUNT_FROZEN");
        }

        @Test
        void failsWithDailyLimitExceeded() {
            Account account = Account.builder()
                    .accountId(ACCOUNT_ID).accountNumber("1111-2222-3333-4444").userId(UUID.randomUUID())
                    .accountType(AccountType.CHECKING).currency("MXN").status(AccountStatus.ACTIVE)
                    .availableBalance(new BigDecimal("100000.00")).reservedAmount(BigDecimal.ZERO)
                    .pendingCredit(BigDecimal.ZERO)
                    .dailyTransactionLimit(new BigDecimal("50000.00"))
                    .dailyTransactionUsed(new BigDecimal("49900.00"))
                    .monthlyTransactionLimit(new BigDecimal("1000000.00"))
                    .monthlyTransactionUsed(BigDecimal.ZERO)
                    .minimumBalance(BigDecimal.ZERO).interestRate(BigDecimal.ZERO)
                    .build();
            when(reservationRepository.findByAccountIdAndTransactionId(ACCOUNT_ID, TXN_ID))
                    .thenReturn(Optional.empty());
            when(lockRepository.tryAcquireLock(eq(ACCOUNT_ID), anyString())).thenReturn(true);
            when(accountRepository.findWithLockById(ACCOUNT_ID)).thenReturn(Optional.of(account));

            var result = service.reserveBalance(ACCOUNT_ID, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).contains("DAILY_LIMIT_EXCEEDED");
        }
    }

    @Nested
    class ReleaseBalance {

        @Test
        void isNoOpWhenNoReservationExists() {
            when(reservationRepository.findByAccountIdAndTransactionId(ACCOUNT_ID, TXN_ID))
                    .thenReturn(Optional.empty());

            var result = service.releaseBalance(ACCOUNT_ID, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(result.success()).isTrue();
            assertThat(result.idempotentReplay()).isTrue();
            verify(accountRepository, never()).findWithLockById(any());
        }

        @Test
        void isIdempotentWhenAlreadyReleased() {
            BalanceReservation released = BalanceReservation.builder()
                    .reservationId(UUID.randomUUID()).accountId(ACCOUNT_ID).transactionId(TXN_ID)
                    .reservedAmount(new BigDecimal("300.00")).status(ReservationStatus.RELEASED).build();
            when(reservationRepository.findByAccountIdAndTransactionId(ACCOUNT_ID, TXN_ID))
                    .thenReturn(Optional.of(released));

            var result = service.releaseBalance(ACCOUNT_ID, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(result.idempotentReplay()).isTrue();
            verify(accountRepository, never()).findWithLockById(any());
        }

        @Test
        void succeedsAndRestoresAvailableBalance() {
            BalanceReservation active = BalanceReservation.builder()
                    .reservationId(UUID.randomUUID()).accountId(ACCOUNT_ID).transactionId(TXN_ID)
                    .reservedAmount(new BigDecimal("300.00")).status(ReservationStatus.ACTIVE).build();
            when(reservationRepository.findByAccountIdAndTransactionId(ACCOUNT_ID, TXN_ID))
                    .thenReturn(Optional.of(active));
            when(accountRepository.findWithLockById(ACCOUNT_ID))
                    .thenReturn(Optional.of(activeAccount(new BigDecimal("700.00"), new BigDecimal("300.00"))));

            var result = service.releaseBalance(ACCOUNT_ID, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(result.success()).isTrue();
            assertThat(result.newAvailableBalance()).isEqualByComparingTo("1000.00");
            verify(reservationRepository).updateStatus(active.getReservationId(), ReservationStatus.RELEASED);
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("BalanceReleased")));
        }
    }

    @Nested
    class FinalizeTransfer {

        private final UUID sourceId = UUID.randomUUID();
        private final UUID targetId = UUID.randomUUID();

        @Test
        void succeedsAndDebitsSourceCreditsTarget() {
            Account source = Account.builder().accountId(sourceId).accountNumber("S-1").userId(UUID.randomUUID())
                    .accountType(AccountType.CHECKING).currency("MXN").status(AccountStatus.ACTIVE)
                    .availableBalance(new BigDecimal("700.00")).reservedAmount(new BigDecimal("300.00"))
                    .pendingCredit(BigDecimal.ZERO).build();
            Account target = Account.builder().accountId(targetId).accountNumber("T-1").userId(UUID.randomUUID())
                    .accountType(AccountType.CHECKING).currency("MXN").status(AccountStatus.ACTIVE)
                    .availableBalance(new BigDecimal("200.00")).reservedAmount(BigDecimal.ZERO)
                    .pendingCredit(BigDecimal.ZERO).build();

            when(reservationRepository.findByAccountIdAndTransactionId(sourceId, TXN_ID))
                    .thenReturn(Optional.empty());
            when(accountRepository.findWithLocksForTransfer(anyList()))
                    .thenReturn(List.of(source, target));

            service.finalizeTransfer(sourceId, targetId, TXN_ID, new BigDecimal("300.00"), "trace-1");

            assertThat(source.getReservedAmount()).isEqualByComparingTo("0.00");
            assertThat(target.getAvailableBalance()).isEqualByComparingTo("500.00");
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("BalanceFinalizedDebit")));
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("BalanceCredited")));
        }

        @Test
        void isIdempotentWhenAlreadyFinalized() {
            BalanceReservation finalized = BalanceReservation.builder()
                    .reservationId(UUID.randomUUID()).accountId(sourceId).transactionId(TXN_ID)
                    .reservedAmount(new BigDecimal("300.00")).status(ReservationStatus.FINALIZED).build();
            when(reservationRepository.findByAccountIdAndTransactionId(sourceId, TXN_ID))
                    .thenReturn(Optional.of(finalized));

            service.finalizeTransfer(sourceId, targetId, TXN_ID, new BigDecimal("300.00"), "trace-1");

            verify(accountRepository, never()).findWithLocksForTransfer(any());
        }

        @Test
        void refusesWhenReservationAlreadyReleased() {
            BalanceReservation released = BalanceReservation.builder()
                    .reservationId(UUID.randomUUID()).accountId(sourceId).transactionId(TXN_ID)
                    .reservedAmount(new BigDecimal("300.00")).status(ReservationStatus.RELEASED).build();
            when(reservationRepository.findByAccountIdAndTransactionId(sourceId, TXN_ID))
                    .thenReturn(Optional.of(released));

            service.finalizeTransfer(sourceId, targetId, TXN_ID, new BigDecimal("300.00"), "trace-1");

            verify(accountRepository, never()).findWithLocksForTransfer(any());
            verify(outboxRepository, never()).save(any());
        }
    }

    @Nested
    class CreditAccount {

        @Test
        void succeedsForDeposit() {
            when(accountEventRepository.findByTransactionIdOrderByOccurredAtAsc(TXN_ID)).thenReturn(List.of());
            when(accountRepository.findWithLocksForTransfer(List.of(ACCOUNT_ID)))
                    .thenReturn(List.of(activeAccount(new BigDecimal("500.00"), BigDecimal.ZERO)));

            service.creditAccount(ACCOUNT_ID, TXN_ID, new BigDecimal("200.00"), "trace-1");

            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("BalanceCredited")));
            verify(balanceCacheRepository).invalidate(ACCOUNT_ID);
        }

        @Test
        void isIdempotentWhenAlreadyCredited() {
            when(accountEventRepository.findByTransactionIdOrderByOccurredAtAsc(TXN_ID))
                    .thenReturn(List.of(mock(com.nexus.account.domain.model.AccountEvent.class)));

            service.creditAccount(ACCOUNT_ID, TXN_ID, new BigDecimal("200.00"), "trace-1");

            verify(accountRepository, never()).findWithLocksForTransfer(any());
        }
    }
}
