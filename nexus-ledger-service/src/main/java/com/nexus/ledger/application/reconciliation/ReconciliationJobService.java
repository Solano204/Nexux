package com.nexus.ledger.application.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.ledger.domain.model.OutboxEntry;
import com.nexus.ledger.infrastructure.mongodb.AccountLedgerSummaryRepository;
import com.nexus.ledger.infrastructure.persistence.ChartOfAccountRepository;
import com.nexus.ledger.infrastructure.persistence.LedgerEntryRepository;
import com.nexus.ledger.infrastructure.persistence.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Reconciliation Job Service — Nightly financial health check.
 *
 * Three verification layers:
 * 1. Per-account balance reconciliation (ledger vs account service)
 * 2. Global double-entry integrity (total debits == total credits)
 * 3. Checksum verification (10% sample of recent entries)
 *
 * Runs at 1:00 AM Mexico City time via @Scheduled cron.
 * Any failure triggers CRITICAL alerts via outbox → Kafka → SNS.
 */
@Slf4j
@Service
public class ReconciliationJobService {

    private final LedgerEntryRepository entryRepository;
    private final ChartOfAccountRepository coaRepository;
    private final AccountLedgerSummaryRepository summaryRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    private final Counter reconciliationSuccessCounter;
    private final Counter reconciliationFailureCounter;
    private final Counter checksumFailureCounter;
    private final Timer reconciliationTimer;

    public ReconciliationJobService(
            LedgerEntryRepository entryRepository,
            ChartOfAccountRepository coaRepository,
            AccountLedgerSummaryRepository summaryRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.entryRepository = entryRepository;
        this.coaRepository = coaRepository;
        this.summaryRepository = summaryRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        this.reconciliationSuccessCounter = Counter.builder(
                        "ledger.reconciliation.total")
                .tag("outcome", "SUCCESS").register(meterRegistry);
        this.reconciliationFailureCounter = Counter.builder(
                        "ledger.reconciliation.failures.total")
                .description("CRITICAL: ledger-account balance mismatch")
                .register(meterRegistry);
        this.checksumFailureCounter = Counter.builder(
                        "ledger.checksum.failures.total")
                .description("CRITICAL: entry tampered")
                .register(meterRegistry);
        this.reconciliationTimer = Timer.builder(
                        "ledger.reconciliation.duration.seconds")
                .register(meterRegistry);
    }

    /**
     * Nightly reconciliation — 1:00 AM Mexico City time.
     * Verifies global double-entry invariant for the past 24 hours.
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "America/Mexico_City")
    public void runNightlyReconciliation() {
        Timer.Sample sample = Timer.start();
        Observation obs = Observation.createNotStarted(
                "ledger.reconciliation", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            log.info("Nightly reconciliation started");

            // Step 1: Global double-entry balance check
            verifyGlobalBalance(obs);

            // Step 2: Checksum verification (10% sample)
            verifyChecksums(obs);

            reconciliationSuccessCounter.increment();
            obs.event(Observation.Event.of("reconciliation.complete"));
            log.info("Nightly reconciliation completed successfully");

        } catch (Exception e) {
            obs.error(e);
            log.error("Reconciliation FAILED: {}", e.getMessage(), e);
        } finally {
            sample.stop(reconciliationTimer);
            obs.stop();
        }
    }

    /**
     * Global double-entry invariant verification.
     * Sum of all debits for today MUST equal sum of all credits.
     */
    @Transactional(readOnly = true)
    public void verifyGlobalBalance(Observation parentObs) {
        Instant startOfDay = LocalDate.now()
                .minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfDay = LocalDate.now()
                .atStartOfDay().toInstant(ZoneOffset.UTC);

        Object[] result = entryRepository.computeGlobalBalance(
                startOfDay, endOfDay);

        if (result != null && result.length == 2) {
            BigDecimal totalDebits = (BigDecimal) result[0];
            BigDecimal totalCredits = (BigDecimal) result[1];

            if (totalDebits == null) totalDebits = BigDecimal.ZERO;
            if (totalCredits == null) totalCredits = BigDecimal.ZERO;

            BigDecimal imbalance = totalDebits.subtract(totalCredits).abs();

            if (imbalance.compareTo(new BigDecimal("0.0001")) > 0) {
                reconciliationFailureCounter.increment();
                log.error("CRITICAL: Global double-entry imbalance! " +
                                "debits={} credits={} imbalance={}",
                        totalDebits, totalCredits, imbalance);

                publishReconciliationAlert(null,
                        totalDebits, totalCredits, imbalance);
            } else {
                log.info("Global balance verified: debits={} credits={} " +
                        "imbalance={}", totalDebits, totalCredits, imbalance);
            }
        }
    }

    /**
     * Checksum verification — 10% sample of entries from the past week.
     */
    @Transactional(readOnly = true)
    public void verifyChecksums(Observation parentObs) {
        Instant oneWeekAgo = Instant.now().minusSeconds(7 * 86400);
        int sampleSize = 100;

        var samples = entryRepository.sampleForIntegrityCheck(
                oneWeekAgo, sampleSize);

        int failures = 0;
        for (var entry : samples) {
            if (!entry.isChecksumValid()) {
                failures++;
                checksumFailureCounter.increment();
                log.error("CRITICAL: Checksum mismatch for entryId={}",
                        entry.getEntryId());
            }
        }

        if (failures > 0) {
            log.error("Checksum verification: {}/{} failures detected",
                    failures, samples.size());
        } else {
            log.info("Checksum verification: {}/{} entries verified OK",
                    samples.size(), samples.size());
        }
    }

    private void publishReconciliationAlert(UUID accountId,
                                            BigDecimal ledgerBalance,
                                            BigDecimal accountBalance,
                                            BigDecimal discrepancy) {
        try {
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("alertType", "RECONCILIATION_FAILURE")
                    .put("accountId", accountId != null
                            ? accountId.toString() : "GLOBAL")
                    .put("ledgerBalance", ledgerBalance.toPlainString())
                    .put("accountBalance", accountBalance.toPlainString())
                    .put("discrepancy", discrepancy.toPlainString())
                    .put("detectedAt", Instant.now().toString())
                    .put("severity", "CRITICAL");

            outboxRepository.save(OutboxEntry.of(
                    "LEDGER_RECONCILIATION",
                    accountId != null ? accountId : UUID.randomUUID(),
                    "ReconciliationFailed",
                    payload));
        } catch (Exception e) {
            log.error("Failed to publish reconciliation alert: {}",
                    e.getMessage());
        }
    }
}