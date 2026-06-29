package com.nexus.audit.write.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.audit.write.model.AuditEvent;
import com.nexus.audit.write.normalizer.AuditEventNormalizer;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.*;
import org.jboss.logging.Logger;

import java.time.*;

@ApplicationScoped
@RegisterForReflection
public class AuditEventConsumer {

    private static final Logger log =
            Logger.getLogger(AuditEventConsumer.class);

    @Inject
    ElasticsearchAsyncClient elasticsearchClient;

    @Inject
    AuditEventNormalizer normalizer;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ComplianceRuleEvaluator ruleEvaluator;

    // ══════════════════════════════════════════════════════════
    // FINANCIAL EVENTS
    // ══════════════════════════════════════════════════════════

    @Incoming("transactions-completed")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onTransactionCompleted(
            KafkaRecord<String, String> record) {
        return processEvent(record, "transactions.completed");
    }

    @Incoming("transactions-failed")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onTransactionFailed(
            KafkaRecord<String, String> record) {
        return processEvent(record, "transactions.failed");
    }

    @Incoming("transactions-initiated")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onTransactionInitiated(
            KafkaRecord<String, String> record) {
        return processEvent(record, "transactions.initiated");
    }

    @Incoming("ledger-posted")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onLedgerPosted(
            KafkaRecord<String, String> record) {
        return processEvent(record, "ledger.posted");
    }

    @Incoming("ledger-reversed")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onLedgerReversed(
            KafkaRecord<String, String> record) {
        return processEvent(record, "ledger.reversed");
    }

    // ══════════════════════════════════════════════════════════
    // SECURITY EVENTS
    // ══════════════════════════════════════════════════════════

    @Incoming("fraud-result")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onFraudResult(
            KafkaRecord<String, String> record) {
        return processEventWithRules(record, "fraud.result");
    }

    @Incoming("fraud-flagged")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onFraudFlagged(
            KafkaRecord<String, String> record) {
        return processEventWithRules(record, "fraud.flagged");
    }

    @Incoming("account-frozen")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onAccountFrozen(
            KafkaRecord<String, String> record) {
        return processEventWithRules(record, "account.frozen");
    }

    // ══════════════════════════════════════════════════════════
    // IDENTITY + COMPLIANCE EVENTS
    // ══════════════════════════════════════════════════════════

    @Incoming("users-registered")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onUserRegistered(
            KafkaRecord<String, String> record) {
        return processEvent(record, "users.registered");
    }

    @Incoming("identity-verified")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onIdentityVerified(
            KafkaRecord<String, String> record) {
        return processEvent(record, "identity.verified");
    }

    @Incoming("identity-rejected")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onIdentityRejected(
            KafkaRecord<String, String> record) {
        return processEvent(record, "identity.rejected");
    }

    @Incoming("accounts-created")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onAccountCreated(
            KafkaRecord<String, String> record) {
        return processEvent(record, "accounts.created");
    }

    // ══════════════════════════════════════════════════════════
    // SAGA + SYSTEM EVENTS
    // ══════════════════════════════════════════════════════════

    @Incoming("saga-completed")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onSagaCompleted(
            KafkaRecord<String, String> record) {
        return processEvent(record, "saga.completed");
    }

    @Incoming("ai-query-logged")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onAiQuery(
            KafkaRecord<String, String> record) {
        return processEvent(record, "ai.query.logged");
    }

    @Incoming("analytics-anomalies")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onAnalyticsAnomaly(
            KafkaRecord<String, String> record) {
        return processEventWithRules(record, "analytics.anomalies");
    }

    // ══════════════════════════════════════════════════════════
    // CORE PROCESSING
    // ══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private long extractOffset(KafkaRecord<String, String> record) {
        return record.getMetadata(IncomingKafkaRecordMetadata.class)
                .map(IncomingKafkaRecordMetadata::getOffset)
                .orElse(-1L);
    }

    /**
     * Base processing: normalize + write to Elasticsearch.
     * Idempotent via op_type=create (409 = already exists = OK).
     * ack() is called in both success and failure paths so the consumer
     * never stalls waiting for an acknowledgement that never arrives.
     */
    private Uni<Void> processEvent(KafkaRecord<String, String> record,
                                   String topic) {
        return Uni.createFrom()
                .item(() -> parseAndNormalize(
                        record.getPayload(), topic, extractOffset(record)))
                .flatMap(this::writeToElasticsearch)
                .onItemOrFailure().invoke((v, t) -> {
                    if (t != null) {
                        log.errorf("Failed to audit event from %s: %s",
                                topic, t.getMessage());
                    }
                    record.ack();
                })
                .onFailure().recoverWithNull();
    }

    /**
     * Enhanced processing: normalize + write + compliance rules.
     * Used for security and fraud events.
     * ack() is always called regardless of processing outcome.
     */
    private Uni<Void> processEventWithRules(
            KafkaRecord<String, String> record, String topic) {
        return Uni.createFrom()
                .item(() -> parseAndNormalize(
                        record.getPayload(), topic, extractOffset(record)))
                .flatMap(event ->
                        writeToElasticsearch(event)
                                .flatMap(v -> ruleEvaluator.evaluate(event)))
                .onItemOrFailure().invoke((v, t) -> {
                    if (t != null) {
                        log.errorf("Failed to audit+evaluate from %s: %s",
                                topic, t.getMessage());
                    }
                    record.ack();
                })
                .onFailure().recoverWithNull();
    }

    private AuditEvent parseAndNormalize(String payload,
                                         String topic,
                                         long offset) {
        try {
            JsonNode raw = objectMapper.readTree(payload);
            return normalizer.normalize(raw, topic, offset);
        } catch (Exception e) {
            log.warnf("Parse error for topic %s: %s",
                    topic, e.getMessage());
            return normalizer.normalize(
                    objectMapper.createObjectNode()
                            .put("rawPayload", payload),
                    topic, offset);
        }
    }

    /**
     * Write to Elasticsearch with idempotency.
     * opType=CREATE: HTTP 409 if document already exists.
     * 409 is treated as success (idempotent replay).
     */
    private Uni<Void> writeToElasticsearch(AuditEvent event) {

        String indexName = "nexus-audit-" +
                event.eventTimestamp()
                        .atZone(ZoneOffset.UTC).getYear() +
                "-" +
                String.format("%02d",
                        event.eventTimestamp()
                                .atZone(ZoneOffset.UTC).getMonthValue());

        IndexRequest<AuditEvent> request = IndexRequest.of(idx ->
                idx.index(indexName)
                        .id(event.eventId())
                        .opType(OpType.Create)
                        .document(event));

        return Uni.createFrom()
                .completionStage(
                        elasticsearchClient.index(request))
                .replaceWithVoid()
                .onFailure(co.elastic.clients.elasticsearch
                        ._types.ElasticsearchException.class)
                .recoverWithUni(e -> {
                    if (e.getMessage().contains("409") ||
                            e.getMessage().contains("version_conflict")) {
                        log.debugf("Idempotent replay: %s", event.eventId());
                        return Uni.createFrom().voidItem();
                    }
                    return Uni.createFrom().failure(e);
                });
    }
}