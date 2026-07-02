package com.nexus.transaction.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.transaction.domain.exception.*;
import com.nexus.transaction.domain.model.*;
import com.nexus.transaction.domain.model.enums.*;
import com.nexus.transaction.infrastructure.analytics.TransactionDynamoDbWriter;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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

    @Autowired(required = false)
    @Nullable
    private TransactionDynamoDbWriter dynamoWriter;
    private final Timer processingTimer;
    private final Counter initiatedCounter;
    private final Counter completedCounter;
    private final Counter failedCounter;
    private final Counter fraudRejectedCounter;
    private final DistributionSummary amountSummary;

    public TransactionCommandService(TransactionRepository transactionRepository, OutboxRepository outboxRepository,
                                     TransactionEventProducer eventProducer, ElasticsearchIndexingService searchIndexer,
                                     ObjectMapper objectMapper, ObservationRegistry observationRegistry, Tracer tracer, MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
        this.eventProducer = eventProducer;
        this.searchIndexer = searchIndexer;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;
        this.processingTimer = Timer.builder("transaction.processing.time").publishPercentiles(0.5, 0.9, 0.95, 0.99).register(meterRegistry);
        this.initiatedCounter = Counter.builder("transaction.initiated.total").register(meterRegistry);
        this.completedCounter = Counter.builder("transaction.completed.total").register(meterRegistry);
        this.failedCounter = Counter.builder("transaction.failed.total").register(meterRegistry);
        this.fraudRejectedCounter = Counter.builder("transaction.fraud.rejected.total").register(meterRegistry);
        this.amountSummary = DistributionSummary.builder("transaction.amount.mxn").publishPercentiles(0.5, 0.9, 0.95, 0.99).register(meterRegistry);
    }

    @Transactional
    public TransactionResponse initiateTransaction(InitiateTransactionRequest request, UUID userId, String ipAddress, String deviceFingerprint, String traceId) {
        Timer.Sample timerSample = Timer.start();
        Observation obs = Observation.createNotStarted("transaction.initiate", observationRegistry).lowCardinalityKeyValue("type", request.transactionType().name()).start();
        try {
            var existing = transactionRepository.findByUserIdAndIdempotencyKey(userId, request.idempotencyKey());
            if (existing.isPresent()) {
                obs.event(Observation.Event.of("transaction.idempotent"));
                return toResponse(existing.get());
            }
            UUID transactionId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            Transaction txn = Transaction.builder().transactionId(transactionId).idempotencyKey(request.idempotencyKey()).userId(userId)
                    .sourceAccountId(request.sourceAccountId()).targetAccountId(request.targetAccountId()).targetAccountNumber(request.targetAccountNumber())
                    .targetUserId(request.targetUserId()).amount(request.amount()).currency(request.currency() != null ? request.currency() : "MXN")
                    .exchangeRate(BigDecimal.ONE).feeAmount(calculateFee(request)).transactionType(request.transactionType())
                    .channel(request.channel() != null ? request.channel() : TransactionChannel.API).description(request.description())
                    .merchantName(request.merchantName()).merchantCategoryCode(request.merchantCategoryCode())
                    .status(TransactionStatus.INITIATED).sagaId(sagaId).sagaStep("INITIATED").ipAddress(ipAddress).deviceFingerprint(deviceFingerprint).build();
            txn = transactionRepository.save(txn);
            writeOutbox("transactions.initiated", transactionId, sagaId, "TransactionInitiated", buildInitiatedPayload(txn, traceId));
            searchIndexer.indexAsync(txn);
            initiatedCounter.increment();
            amountSummary.record(request.amount().doubleValue());
            log.info("Transaction initiated: txnId={} sagaId={} type={} amount={}", transactionId, sagaId, request.transactionType(), request.amount());
            return toResponse(txn);
        } finally {
            timerSample.stop(processingTimer);
            obs.stop();
        }
    }

    @Transactional
    public void processFraudResult(UUID transactionId, UUID sagaId, boolean cleared, BigDecimal score, java.util.List<String> reasons, String traceId) {
        Transaction txn = loadTransaction(transactionId);
        if (txn.getStatus() == TransactionStatus.FAILED || txn.getStatus() == TransactionStatus.COMPLETED) return;
        if (cleared) {
            txn.markFraudCleared(score, reasons);
            transactionRepository.save(txn);
            // Saga orchestrator drives the next step (PostLedgerCommand) — no outbox needed here
        } else {
            txn.markFraudRejected(score, reasons);
            txn.markFailed("FRAUD_REJECTED");
            transactionRepository.save(txn);
            writeOutbox("transactions.failed", transactionId, sagaId, "TransactionFraudRejected", buildFraudRejectedPayload(txn, reasons, traceId));
            searchIndexer.indexAsync(txn);
            if (dynamoWriter != null) dynamoWriter.writeFailed(txn);
            fraudRejectedCounter.increment();
        }
    }

    @Transactional
    public void processBalanceResult(UUID transactionId, UUID sagaId, boolean success, String failureReason, String traceId) {
        Transaction txn = loadTransaction(transactionId);
        if (success) {
            if (txn.getStatus() == TransactionStatus.INITIATED) {
                txn.markBalanceReserving();
                transactionRepository.saveAndFlush(txn);
            }
            txn.markBalanceReserved();
            transactionRepository.save(txn);
            // Saga-orchestrator handles ledger posting after fraud check;
            // do not send PostLedgerCommand here to avoid posting before fraud decision.
        } else {
            if (txn.getStatus() == TransactionStatus.INITIATED) {
                txn.markBalanceReserving();
            }
            txn.markReserveFailed(failureReason);
            txn.markFailed(failureReason);
            transactionRepository.save(txn);
            writeOutbox("transactions.failed", transactionId, sagaId, "TransactionFailed", buildFailedPayload(txn, failureReason, traceId));
            searchIndexer.indexAsync(txn);
            if (dynamoWriter != null) dynamoWriter.writeFailed(txn);
            failedCounter.increment();
        }
    }
    @Transactional
    public void processLedgerResult(UUID transactionId, UUID sagaId, boolean success, UUID ledgerEntryId, String failureReason, String traceId) {
        Transaction txn = loadTransaction(transactionId);
        if (txn.getStatus() == TransactionStatus.COMPLETED) return;
        if (success) {
            if (txn.getStatus() != TransactionStatus.LEDGER_POSTING) {
                txn.markLedgerPosting();
                transactionRepository.saveAndFlush(txn);
            }
            txn.markLedgerPosted(ledgerEntryId); transactionRepository.saveAndFlush(txn); txn.markCompleted(); transactionRepository.save(txn);
            writeOutbox("transactions.completed", transactionId, sagaId, "TransactionCompleted", buildCompletedPayload(txn, traceId));
            writeOutbox("user.behavior.aggregated", transactionId, sagaId, "TransactionCompleted", buildBehaviorPayload(txn, traceId));
            searchIndexer.indexAsync(txn);
            if (dynamoWriter != null) dynamoWriter.writeCompleted(txn);
            completedCounter.increment();
        } else {
            txn.markLedgerFailed(failureReason); transactionRepository.save(txn);
            writeOutbox("saga.commands", transactionId, sagaId, "BalanceReleaseRequested", buildBalanceReleasePayload(txn, traceId));
            writeOutbox("transactions.failed", transactionId, sagaId, "TransactionFailed", buildFailedPayload(txn, failureReason, traceId));
            searchIndexer.indexAsync(txn);
            if (dynamoWriter != null) dynamoWriter.writeFailed(txn);
            failedCounter.increment();
        }
    }

    private Transaction loadTransaction(UUID id) { return transactionRepository.findById(id).orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + id)); }
    private void writeOutbox(String topic, UUID aggregateId, UUID sagaId, String eventType, ObjectNode payload) { payload.put("sagaId", sagaId.toString()); outboxRepository.save(OutboxEntry.of(topic, aggregateId, eventType, payload)); }
    private BigDecimal calculateFee(InitiateTransactionRequest req) { return req.transactionType() == TransactionType.EXTERNAL_TRANSFER ? req.amount().multiply(new BigDecimal("0.015")).setScale(4, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO; }

    private ObjectNode buildInitiatedPayload(Transaction t, String traceId) { return objectMapper.createObjectNode().put("transactionId",t.getTransactionId().toString()).put("sagaId",t.getSagaId().toString()).put("userId",t.getUserId().toString()).put("sourceAccountId",t.getSourceAccountId().toString()).put("targetAccountId",t.getTargetAccountId()!=null?t.getTargetAccountId().toString():null).put("amount",t.getAmount().toPlainString()).put("currency",t.getCurrency()).put("transactionType",t.getTransactionType().name()).put("ipAddress",t.getIpAddress()).put("traceId",traceId).put("initiatedAt",Instant.now().toString()); }
    private ObjectNode buildBalanceReservationPayload(Transaction t, String traceId) { return objectMapper.createObjectNode().put("commandType","ReserveBalanceCommand").put("targetService","nexus-account-service").put("transactionId",t.getTransactionId().toString()).put("accountId",t.getSourceAccountId().toString()).put("amount",t.getAmount().toPlainString()).put("currency",t.getCurrency()).put("traceId",traceId); }
    private ObjectNode buildLedgerPostingPayload(Transaction t, String traceId) { return objectMapper.createObjectNode().put("commandType","PostLedgerCommand").put("targetService","nexus-ledger-service").put("transactionId",t.getTransactionId().toString()).put("sourceAccountId",t.getSourceAccountId().toString()).put("targetAccountId",t.getTargetAccountId()!=null?t.getTargetAccountId().toString():null).put("amount",t.getAmount().toPlainString()).put("currency",t.getCurrency()).put("transactionType",t.getTransactionType().name()).put("traceId",traceId); }
    private ObjectNode buildBalanceReleasePayload(Transaction t, String traceId) { return objectMapper.createObjectNode().put("commandType","ReleaseBalanceCommand").put("targetService","nexus-account-service").put("transactionId",t.getTransactionId().toString()).put("accountId",t.getSourceAccountId().toString()).put("amount",t.getAmount().toPlainString()).put("traceId",traceId); }
    private ObjectNode buildCompletedPayload(Transaction t, String traceId) { return objectMapper.createObjectNode().put("transactionId",t.getTransactionId().toString()).put("sourceUserId",t.getUserId().toString()).put("targetUserId",t.getTargetUserId()!=null?t.getTargetUserId().toString():null).put("sourceAccountId",t.getSourceAccountId().toString()).put("targetAccountId",t.getTargetAccountId()!=null?t.getTargetAccountId().toString():null).put("amount",t.getAmount().toPlainString()).put("currency",t.getCurrency()).put("transactionType",t.getTransactionType().name()).put("merchantName",t.getMerchantName()).put("merchantCategoryCode",t.getMerchantCategoryCode()).put("ledgerEntryId",t.getLedgerEntryId().toString()).put("completedAt",Instant.now().toString()).put("traceId",traceId); }
    private ObjectNode buildBehaviorPayload(Transaction t, String traceId) { return objectMapper.createObjectNode().put("userId",t.getUserId().toString()).put("trigger","TRANSACTION_COMPLETED").put("transactionId",t.getTransactionId().toString()).put("amount",t.getAmount().toPlainString()).put("transactionType",t.getTransactionType().name()).put("traceId",traceId); }
    private ObjectNode buildFraudRejectedPayload(Transaction t, java.util.List<String> reasons, String traceId) { var n=objectMapper.createObjectNode().put("transactionId",t.getTransactionId().toString()).put("userId",t.getUserId().toString()).put("amount",t.getAmount().toPlainString()).put("fraudScore",t.getFraudScore().toPlainString()).put("traceId",traceId); var a=objectMapper.createArrayNode(); reasons.forEach(a::add); n.set("fraudReasons",a); return n; }
    private ObjectNode buildFailedPayload(Transaction t, String reason, String traceId) { return objectMapper.createObjectNode().put("transactionId",t.getTransactionId().toString()).put("userId",t.getUserId().toString()).put("amount",t.getAmount().toPlainString()).put("failureReason",reason).put("traceId",traceId); }
    private TransactionResponse toResponse(Transaction t) { return new TransactionResponse(t.getTransactionId().toString(),t.getStatus().name(),t.getAmount(),t.getCurrency(),t.getTransactionType().name(),t.getDescription(),t.getInitiatedAt().toString(),t.getCompletedAt()!=null?t.getCompletedAt().toString():null,t.getFailureReason()); }
}