package com.nexus.account.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.account.application.command.AccountCommandService;
import com.nexus.account.infrastructure.mongodb.AccountAnalyticsRepository;
import com.nexus.account.infrastructure.persistence.*;
import com.nexus.account.infrastructure.redis.BalanceCacheRepository;
import com.nexus.account.infrastructure.redis.ReservationLockRepository;
import com.nexus.account.integration.AccountIntegrationEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Locking behavior in isolation from Postgres/Redis — the real
 * cross-process race is covered by BalanceConcurrencyIntegrationTest
 * (Testcontainers). This class pins the retry/backoff contract of
 * AccountCommandService.acquireLockWithRetry() and the Lua-script-backed
 * atomicity of ReservationLockRepository, both via mocks only.
 */
class BalanceLockingTest {

    @Nested
    @ExtendWith(MockitoExtension.class)
    class AcquireLockWithRetry {

        @Mock private AccountRepository accountRepository;
        @Mock private BalanceReservationRepository reservationRepository;
        @Mock private AccountEventRepository accountEventRepository;
        @Mock private OutboxRepository outboxRepository;
        @Mock private AccountAnalyticsRepository analyticsRepository;
        @Mock private BalanceCacheRepository balanceCacheRepository;
        @Mock private ReservationLockRepository lockRepository;
        @Mock private Tracer tracer;

        private AccountCommandService service;

        @BeforeEach
        void setUp() {
            service = new AccountCommandService(accountRepository, reservationRepository,
                    accountEventRepository, outboxRepository, analyticsRepository, balanceCacheRepository,
                    lockRepository, new ObjectMapper(), ObservationRegistry.NOOP, tracer,
                    new SimpleMeterRegistry(), new AccountIntegrationEventMapper());
        }

        private boolean invoke(UUID accountId, String txnId, int maxRetries, long delayMs) throws Exception {
            Method m = AccountCommandService.class.getDeclaredMethod(
                    "acquireLockWithRetry", UUID.class, String.class, int.class, long.class);
            m.setAccessible(true);
            return (boolean) m.invoke(service, accountId, txnId, maxRetries, delayMs);
        }

        @Test
        void succeedsImmediatelyWithoutRetryingWhenLockFreeOnFirstAttempt() throws Exception {
            UUID accountId = UUID.randomUUID();
            when(lockRepository.tryAcquireLock(eq(accountId), anyString())).thenReturn(true);

            boolean acquired = invoke(accountId, "txn-1", 3, 1);

            assertThat(acquired).isTrue();
            verify(lockRepository, times(1)).tryAcquireLock(eq(accountId), anyString());
        }

        @Test
        void retriesUntilLockFreesUpWithinMaxAttempts() throws Exception {
            UUID accountId = UUID.randomUUID();
            when(lockRepository.tryAcquireLock(eq(accountId), anyString()))
                    .thenReturn(false, false, true);

            boolean acquired = invoke(accountId, "txn-1", 3, 1);

            assertThat(acquired).isTrue();
            verify(lockRepository, times(3)).tryAcquireLock(eq(accountId), anyString());
        }

        @Test
        void failsAfterExhaustingAllRetriesWithoutAcquiring() throws Exception {
            UUID accountId = UUID.randomUUID();
            when(lockRepository.tryAcquireLock(eq(accountId), anyString())).thenReturn(false);

            boolean acquired = invoke(accountId, "txn-1", 3, 1);

            assertThat(acquired).isFalse();
            verify(lockRepository, times(3)).tryAcquireLock(eq(accountId), anyString());
        }

        @Test
        void neverSleepsAfterTheFinalAttempt() throws Exception {
            // maxRetries=1 means exactly one tryAcquireLock call and zero
            // Thread.sleep() calls — verified indirectly by the call count
            // above staying at 1 even though the loop body always checks
            // "attempt < maxRetries - 1" before sleeping.
            UUID accountId = UUID.randomUUID();
            when(lockRepository.tryAcquireLock(eq(accountId), anyString())).thenReturn(false);

            long start = System.nanoTime();
            boolean acquired = invoke(accountId, "txn-1", 1, 5000);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(acquired).isFalse();
            assertThat(elapsedMs).isLessThan(1000);
            verify(lockRepository, times(1)).tryAcquireLock(eq(accountId), anyString());
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class ReservationLockRepositoryBehavior {

        @Mock private StringRedisTemplate redisTemplate;
        @Mock private ValueOperations<String, String> valueOperations;

        private ReservationLockRepository repository;

        @BeforeEach
        void setUp() {
            repository = new ReservationLockRepository(redisTemplate);
        }

        @Test
        void tryAcquireLockUsesSetIfAbsentWithTenSecondTtl() {
            UUID accountId = UUID.randomUUID();
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(eq("account:reservation-lock:" + accountId),
                    eq("txn-1"), eq(Duration.ofSeconds(10)))).thenReturn(true);

            boolean acquired = repository.tryAcquireLock(accountId, "txn-1");

            assertThat(acquired).isTrue();
        }

        @Test
        void tryAcquireLockReturnsFalseWhenAlreadyHeld() {
            UUID accountId = UUID.randomUUID();
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

            assertThat(repository.tryAcquireLock(accountId, "txn-1")).isFalse();
        }

        @Test
        void tryAcquireLockTreatsNullRedisResponseAsNotAcquired() {
            UUID accountId = UUID.randomUUID();
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

            assertThat(repository.tryAcquireLock(accountId, "txn-1")).isFalse();
        }

        @SuppressWarnings("unchecked")
        @Test
        void releaseLockExecutesCompareAndDeleteScriptWithOwningTransactionId() {
            UUID accountId = UUID.randomUUID();

            repository.releaseLock(accountId, "txn-1");

            verify(redisTemplate).execute(
                    any(DefaultRedisScript.class),
                    eq(List.of("account:reservation-lock:" + accountId)),
                    eq("txn-1"));
        }

        @Test
        void releaseLockSwallowsRedisFailuresRatherThanThrowing() {
            UUID accountId = UUID.randomUUID();
            when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                    .thenThrow(new RuntimeException("redis down"));

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> repository.releaseLock(accountId, "txn-1"));
        }

        @Test
        void isLockedReflectsRedisKeyPresence() {
            UUID accountId = UUID.randomUUID();
            when(redisTemplate.hasKey("account:reservation-lock:" + accountId)).thenReturn(true);

            assertThat(repository.isLocked(accountId)).isTrue();
        }

        @Test
        void isLockedReturnsFalseWhenKeyAbsent() {
            UUID accountId = UUID.randomUUID();
            when(redisTemplate.hasKey(anyString())).thenReturn(false);

            assertThat(repository.isLocked(accountId)).isFalse();
        }
    }
}
