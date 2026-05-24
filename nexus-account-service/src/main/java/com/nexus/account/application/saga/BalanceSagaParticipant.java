package com.nexus.account.application.saga;

import com.nexus.account.application.command.AccountCommandService;
import com.nexus.account.domain.model.BalanceReservation;
import com.nexus.account.infrastructure.persistence.AccountRepository;
import com.nexus.account.infrastructure.persistence.BalanceReservationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BalanceSagaParticipant — Scheduled housekeeping for SAGA operations.
 *
 * Handles:
 * 1. Expired reservation cleanup (24h TTL safety net)
 * 2. Daily limit reset (midnight job)
 *
 * The 24h TTL on reservations is a safety net: if the saga orchestrator
 * crashes and never sends a finalize or release command, the reserved
 * funds would be stuck forever. This job auto-releases them.
 *
 * This is NOT the primary SAGA participant — that's SagaCommandConsumer
 * (Kafka) and InternalAccountController (HTTP). This handles edge cases.
 */
@Slf4j
@Component
public class BalanceSagaParticipant {

    private final BalanceReservationRepository reservationRepository;
    private final AccountCommandService commandService;
    private final AccountRepository accountRepository;
    private final Counter expiredReservationsCounter;

    public BalanceSagaParticipant(
            BalanceReservationRepository reservationRepository,
            AccountCommandService commandService,
            AccountRepository accountRepository,
            MeterRegistry meterRegistry) {
        this.reservationRepository = reservationRepository;
        this.commandService = commandService;
        this.accountRepository = accountRepository;

        this.expiredReservationsCounter = Counter.builder(
                        "account.reservation.expired.released.total")
                .description("Reservations auto-released by TTL expiry")
                .register(meterRegistry);
    }

    /**
     * Expired reservation cleanup — runs every 5 minutes.
     *
     * Finds ACTIVE reservations past their 24h expiresAt timestamp
     * and releases the funds back to available balance.
     *
     * This is a safety net for when the saga orchestrator fails to
     * send a FinalizeTransfer or ReleaseBalance command.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)  // 5min
    public void releaseExpiredReservations() {
        List<BalanceReservation> expired =
                reservationRepository.findExpiredActiveReservations();

        if (expired.isEmpty()) return;

        log.warn("Found {} expired reservations to auto-release",
                expired.size());

        for (BalanceReservation reservation : expired) {
            try {
                commandService.releaseBalance(
                        reservation.getAccountId(),
                        reservation.getTransactionId(),
                        reservation.getReservedAmount(),
                        "auto-expiry-cleanup");

                expiredReservationsCounter.increment();
                log.info("Auto-released expired reservation: " +
                                "reservationId={} accountId={} transactionId={} " +
                                "amount={}",
                        reservation.getReservationId(),
                        reservation.getAccountId(),
                        reservation.getTransactionId(),
                        reservation.getReservedAmount());

            } catch (Exception e) {
                log.error("Failed to auto-release expired reservation: " +
                                "reservationId={} error={}",
                        reservation.getReservationId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Daily limit reset — runs at midnight Mexico City time.
     *
     * Resets dailyTransactionUsed to 0 for all active accounts
     * that haven't been reset today.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "America/Mexico_City")
    public void resetDailyLimits() {
        log.info("Starting daily limit reset job");

        var accountsDueForReset = accountRepository
                .findAccountsDueForDailyReset(
                        java.time.Instant.now().minus(java.time.Duration.ofHours(20)));

        int resetCount = 0;
        for (var account : accountsDueForReset) {
            try {
                account.resetDailyLimit();
                accountRepository.save(account);
                resetCount++;
            } catch (Exception e) {
                log.error("Failed to reset daily limit for accountId={}: {}",
                        account.getAccountId(), e.getMessage());
            }
        }

        log.info("Daily limit reset completed: {} accounts reset", resetCount);
    }
}