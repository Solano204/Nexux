package com.nexus.transaction.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.transaction.domain.exception.*;
import com.nexus.transaction.domain.model.*;
import com.nexus.transaction.domain.model.enums.*;
import com.nexus.transaction.infrastructure.elasticsearch.ElasticsearchIndexingService;
import com.nexus.transaction.infrastructure.kafka.TransactionEventProducer;
import com.nexus.transaction.infrastructure.persistence.*;
import com.nexus.transaction.web.dto.request.InitiateTransactionRequest;
import com.nexus.transaction.web.dto.response.TransactionResponse;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transaction Command Service — CQRS Write Side.
 *
 * Orchestrates the full transaction lifecycle:
 * 1. Validate + create with idempotency check
 * 2. Publish TransactionInitiated via Outbox
 * 3. SAGA choreography: reacts to fraud/ledger results via Kafka
 * 4. State machine transitions enforced at every step
 * 5. Elasticsearch indexing for search
 *
 * Metrics: P95 latency for transaction.processing.time
 */
@Slf4j
@Service
public class TransactionCommandService {

    private final TransactionRepository transactionRepository;
    private final OutboxRepository outboxRepository;
    private final TransactionEventProducer eventProducer;
    private final ElasticsearchIndexingService searchIndexer;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;

    // Metrics
    private final Timer processingTimer;
    private final Counter initiatedCounter;
    private final Counter completedCounter;
    private final Counter failedCounter;
    private final Counter fraudRejectedCounter;
    private final DistributionSummary amountSummary;

    public TransactionCommandService(
            TransactionRepository transactionRepository,
            OutboxRepository outboxRepository,
            TransactionEventProducer eventProducer,
            ElasticsearchIndexingService searchIndexer,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            MeterRegistry meterRegistry) {

        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
        this.eventProducer = eventProducer;
        this.searchIndexer = searchIndexer;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;

        this.processingTimer = Timer.builder("transaction.processing.time")
                .description("End-to-end transaction processing latency")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .tags("service", "nexus-transaction-service")
                .register(meterRegistry);

        this.initiatedCounter = Counter.builder("transaction.initiated.total")
                .register(meterRegistry);
        this.completedCounter = Counter.builder("transaction.completed.total")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("transaction.failed.total")
                .register(meterRegistry);
        this.fraudRejectedCounter =
                Counter.builder("transaction.fraud.rejected.total")
                        .register(meterRegistry);
        this.amountSummary =
                DistributionSummary.builder("transaction.amount.mxn")
                        .description("Transaction amounts in MXN")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry);
    }

    // ══════════════════════════════════════════════════════
    // INITIATE TRANSACTION
    // ══════════════════════════════════════════════════════

    /**
     * Initiates a new financial transaction.
     *
     * Steps (all in one @Transactional):
     * 1. Idempotency check (same key = return existing)
     * 2. Validate request fields
     * 3. Create Transaction entity (status = INITIATED)
     * 4. Write OutboxEntry (TransactionInitiated event)
     * 5. Debezium publishes event → transactions.initiated
     * 6. SAGA choreography continues asynchronously
     */
    @Transactional
    public TransactionResponse initiateTransaction(
            InitiateTransactionRequest request,
            UUID userId, String ipAddress,
            String deviceFingerprint, String traceId) {

        Timer.Sample timerSample = Timer.start();
        Observation obs = Observation.createNotStarted(
                        "transaction.initiate", observationRegistry)
                .lowCardinalityKeyValue(
                        "type", request.transactionType().name())
                .start();

        try {
            // ── Idempotency check ─────────────────────────
            var existing = transactionRepository
                    .findByUserIdAndIdempotencyKey(
                            userId, request.idempotencyKey());

            if (existing.isPresent()) {
                log.info("Idempotent replay: transactionId={}",
                        existing.get().getTransactionId());
                obs.event(Observation.Event.of(
                        "transaction.idempotent"));
                return toResponse(existing.get());
            }

            UUID transactionId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();

            // ── Build transaction entity ──────────────────
            Transaction txn = Transaction.builder()
                    .transactionId(transactionId)
                    .idempotencyKey(request.idempotencyKey())
                    .userId(userId)
                    .sourceAccountId(request.sourceAccountId())
                    .targetAccountId(request.targetAccountId())
                    .targetAccountNumber(request.targetAccountNumber())
                    .targetUserId(request.targetUserId())
                    .amount(request.amount())
                    .currency(request.currency() != null
                            ? request.currency() : "MXN")
                    .exchangeRate(BigDecimal.ONE)
                    .feeAmount(calculateFee(request))
                    .transactionType(request.transactionType())
                    .channel(request.channel() != null
                            ? request.channel() : TransactionChannel.API)
                    .description(request.description())
                    .merchantName(request.merchantName())
                    .merchantCategoryCode(request.merchantCategoryCode())
                    .status(TransactionStatus.INITIATED)
                    .sagaId(sagaId)
                    .sagaStep("INITIATED")
                    .ipAddress(ipAddress)
                    .deviceFingerprint(deviceFingerprint)
                    .build();

            transactionRepository.save(txn);

            // ── Outbox entry — Debezium publishes TransactionInitiated ──
            writeOutbox(transactionId, sagaId,
                    "TransactionInitiated",
                    buildInitiatedPayload(txn, traceId));

            // ── Index in Elasticsearch for search ─────────
            searchIndexer.indexAsync(txn);

            initiatedCounter.increment();
            amountSummary.record(request.amount().doubleValue());
            obs.event(Observation.Event.of("transaction.initiated"));

            log.info("Transaction initiated: txnId={} sagaId={} " +
                            "type={} amount={} userId={} traceId={}",
                    transactionId, sagaId,
                    request.transactionType(),
                    request.amount(), userId, traceId);

            return toResponse(txn);

        } finally {
            timerSample.stop(processingTimer);
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════
    // FRAUD RESULT HANDLER (from fraud.result topic)
    // ══════════════════════════════════════════════════════

    @Transactional
    public void processFraudResult(UUID transactionId,
                                   UUID sagaId,
                                   boolean cleared,
                                   BigDecimal score,
                                   java.util.List<String> reasons,
                                   String traceId) {

        Observation obs = Observation.createNotStarted(
                "transaction.fraud.result", observationRegistry).start();

        try {
            Transaction txn = loadTransaction(transactionId);

            if (cleared) {
                txn.markFraudCleared(score, reasons);
                transactionRepository.save(txn);

                // Continue SAGA: publish BalanceReservationRequested
                txn.markBalanceReserving();
                transactionRepository.save(txn);

                writeOutbox(transactionId, sagaId,
                        "BalanceReservationRequested",
                        buildBalanceReservationPayload(txn, traceId));

                obs.event(Observation.Event.of("fraud.cleared"));
                log.info("Fraud cleared, balance reservation initiated: " +
                        "txnId={} score={}", transactionId, score);

            } else {
                txn.markFraudRejected(score, reasons);
                txn.markFailed("FRAUD_REJECTED");
                transactionRepository.save(txn);

                writeOutbox(transactionId, sagaId,
                        "TransactionFraudRejected",
                        buildFraudRejectedPayload(txn, reasons, traceId));

                searchIndexer.indexAsync(txn);
                fraudRejectedCounter.increment();
                obs.event(Observation.Event.of("fraud.rejected"));

                log.warn("Transaction fraud rejected: txnId={} " +
                        "score={} reasons={}", transactionId, score, reasons);
            }

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════
    // BALANCE RESULT HANDLER (from saga.replies topic)
    // ══════════════════════════════════════════════════════

    @Transactional
    public void processBalanceResult(UUID transactionId,
                                     UUID sagaId,
                                     boolean success,
                                     String failureReason,
                                     String traceId) {

        Observation obs = Observation.createNotStarted(
                "transaction.balance.result", observationRegistry).start();

        try {
            Transaction txn = loadTransaction(transactionId);

            if (success) {
                txn.markBalanceReserved();
                transactionRepository.save(txn);

                // Continue SAGA: publish LedgerPostingRequested
                txn.markLedgerPosting();
                transactionRepository.save(txn);

                writeOutbox(transactionId, sagaId,
                        "LedgerPostingRequested",
                        buildLedgerPostingPayload(txn, traceId));

                obs.event(Observation.Event.of("balance.reserved"));
                log.info("Balance reserved, ledger posting initiated: " +
                        "txnId={}", transactionId);
            } else {
                txn.markReserveFailed(failureReason);
                txn.markFailed(failureReason);
                transactionRepository.save(txn);

                writeOutbox(transactionId, sagaId,
                        "TransactionFailed",
                        buildFailedPayload(txn, failureReason, traceId));

                searchIndexer.indexAsync(txn);
                failedCounter.increment();
                obs.event(Observation.Event.of("balance.reserve.failed"));
            }

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════
    // LEDGER RESULT HANDLER (from ledger.result topic)
    // ══════════════════════════════════════════════════════

    @Transactional
    public void processLedgerResult(UUID transactionId,
                                    UUID sagaId,
                                    boolean success,
                                    UUID ledgerEntryId,
                                    String failureReason,
                                    String traceId) {

        Observation obs = Observation.createNotStarted(
                "transaction.ledger.result", observationRegistry).start();

        try {
            Transaction txn = loadTransaction(transactionId);

            if (success) {
                txn.markLedgerPosted(ledgerEntryId);
                txn.markCompleted();
                transactionRepository.save(txn);

                writeOutbox(transactionId, sagaId,
                        "TransactionCompleted",
                        buildCompletedPayload(txn, traceId));

                searchIndexer.indexAsync(txn);
                completedCounter.increment();
                obs.event(Observation.Event.of("transaction.completed"));

                log.info("Transaction COMPLETED: txnId={} " +
                                "ledgerEntryId={} amount={}",
                        transactionId, ledgerEntryId,
                        txn.getAmount());

            } else {
                txn.markLedgerFailed(failureReason);
                transactionRepository.save(txn);

                // Need to release balance reservation
                writeOutbox(transactionId, sagaId,
                        "BalanceReleaseRequested",
                        buildBalanceReleasePayload(txn, traceId));

                writeOutbox(transactionId, sagaId,
                        "TransactionFailed",
                        buildFailedPayload(txn, failureReason, traceId));

                searchIndexer.indexAsync(txn);
                failedCounter.increment();
                obs.event(Observation.Event.of("ledger.failed"));

                log.warn("Ledger failed for txnId={}, " +
                                "balance will be released: {}", transactionId,
                        failureReason);
            }

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════

    private Transaction loadTransaction(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction not found: " + id));
    }

    private void writeOutbox(UUID aggregateId, UUID sagaId,
                             String eventType, ObjectNode payload) {
        payload.put("sagaId", sagaId.toString());
        outboxRepository.save(OutboxEntry.of(
                "TRANSACTION", aggregateId, eventType, payload));
    }

    private BigDecimal calculateFee(InitiateTransactionRequest req) {
        // Fee schedule: 0 for internal, 1.5% for external
        return switch (req.transactionType()) {
            case INTERNAL_TRANSFER -> BigDecimal.ZERO;
            case EXTERNAL_TRANSFER -> req.amount()
                    .multiply(new BigDecimal("0.015"))
                    .setScale(4, java.math.RoundingMode.HALF_UP);
            case PAYMENT -> BigDecimal.ZERO;
            default -> BigDecimal.ZERO;
        };
    }

    private ObjectNode buildInitiatedPayload(Transaction t,
                                             String traceId) {
        return objectMapper.createObjectNode()
                .put("transactionId", t.getTransactionId().toString())
                .put("sagaId", t.getSagaId().toString())
                .put("userId", t.getUserId().toString())
                .put("sourceAccountId", t.getSourceAccountId().toString())
                .put("targetAccountId", t.getTargetAccountId() != null
                        ? t.getTargetAccountId().toString() : null)
                .put("amount", t.getAmount().toPlainString())
                .put("currency", t.getCurrency())
                .put("transactionType", t.getTransactionType().name())
                .put("ipAddress", t.getIpAddress())
                .put("deviceFingerprint", t.getDeviceFingerprint())
                .put("traceId", traceId)
                .put("initiatedAt", Instant.now().toString());
    }

    private ObjectNode buildBalanceReservationPayload(
            Transaction t, String traceId) {
        return objectMapper.createObjectNode()
                .put("commandType", "ReserveBalanceCommand")
                .put("targetService", "nexus-account-service")
                .put("transactionId", t.getTransactionId().toString())
                .put("accountId", t.getSourceAccountId().toString())
                .put("amount", t.getAmount().toPlainString())
                .put("currency", t.getCurrency())
                .put("traceId", traceId);
    }

    private ObjectNode buildLedgerPostingPayload(
            Transaction t, String traceId) {
        return objectMapper.createObjectNode()
                .put("transactionId", t.getTransactionId().toString())
                .put("sourceAccountId", t.getSourceAccountId().toString())
                .put("targetAccountId", t.getTargetAccountId() != null
                        ? t.getTargetAccountId().toString() : null)
                .put("amount", t.getAmount().toPlainString())
                .put("netAmount", t.getNetAmount() != null
                        ? t.getNetAmount().toPlainString() : t.getAmount().toPlainString())
                .put("currency", t.getCurrency())
                .put("transactionType", t.getTransactionType().name())
                .put("description", t.getDescription())
                .put("traceId", traceId);
    }

    private ObjectNode buildBalanceReleasePayload(
            Transaction t, String traceId) {
        return objectMapper.createObjectNode()
                .put("commandType", "ReleaseBalanceCommand")
                .put("targetService", "nexus-account-service")
                .put("transactionId", t.getTransactionId().toString())
                .put("accountId", t.getSourceAccountId().toString())
                .put("amount", t.getAmount().toPlainString())
                .put("traceId", traceId);
    }

    private ObjectNode buildCompletedPayload(Transaction t,
                                             String traceId) {
        return objectMapper.createObjectNode()
                .put("transactionId", t.getTransactionId().toString())
                .put("userId", t.getUserId().toString())
                .put("sourceAccountId", t.getSourceAccountId().toString())
                .put("targetAccountId", t.getTargetAccountId() != null
                        ? t.getTargetAccountId().toString() : null)
                .put("amount", t.getAmount().toPlainString())
                .put("currency", t.getCurrency())
                .put("ledgerEntryId", t.getLedgerEntryId().toString())
                .put("completedAt", Instant.now().toString())
                .put("traceId", traceId);
    }

    private ObjectNode buildFraudRejectedPayload(
            Transaction t, java.util.List<String> reasons,
            String traceId) {
        var node = objectMapper.createObjectNode()
                .put("transactionId", t.getTransactionId().toString())
                .put("userId", t.getUserId().toString())
                .put("amount", t.getAmount().toPlainString())
                .put("fraudScore", t.getFraudScore().toPlainString())
                .put("traceId", traceId);
        var arr = objectMapper.createArrayNode();
        reasons.forEach(arr::add);
        node.set("fraudReasons", arr);
        return node;
    }

    private ObjectNode buildFailedPayload(Transaction t,
                                          String reason,
                                          String traceId) {
        return objectMapper.createObjectNode()
                .put("transactionId", t.getTransactionId().toString())
                .put("userId", t.getUserId().toString())
                .put("amount", t.getAmount().toPlainString())
                .put("failureReason", reason)
                .put("traceId", traceId);
    }

    private TransactionResponse toResponse(Transaction t) {
        return new TransactionResponse(
                t.getTransactionId().toString(),
                t.getStatus().name(),
                t.getAmount(),
                t.getCurrency(),
                t.getTransactionType().name(),
                t.getDescription(),
                t.getInitiatedAt().toString(),
                t.getCompletedAt() != null
                        ? t.getCompletedAt().toString() : null,
                t.getFailureReason()
        );
    }
}