package com.nexus.audit.write.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.audit.write.model.AuditEvent;
import com.nexus.audit.write.normalizer.AuditEventNormalizer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.*;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * identity.events carries LoginSuccessful, PasswordResetRequested,
     * PasswordResetCompleted, etc. — every other identity.* topic
     * (users.registered, identity.verified, identity.rejected) is already
     * wired here, but this one was never bound to a channel, so none of
     * these choreographed flows exist in the central audit trail today.
     * This is the read side of the CQRS view for the password-reset
     * choreography: query nexus-audit-* by userId to see
     * PasswordResetRequested / PasswordResetCompleted for a given user,
     * with zero involvement in the actual request/confirm business logic.
     */
    @Incoming("identity-events")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> onIdentityEvent(
            KafkaRecord<String, String> record) {
        return processEvent(record, "identity.events");
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
        Span span = startReceiveSpan(record, topic);
        return Uni.createFrom()
                .item(() -> parseAndNormalize(
                        record.getPayload(), topic, extractOffset(record)))
                .flatMap(this::writeToElasticsearch)
                .onItemOrFailure().invoke((v, t) -> {
                    if (t != null) {
                        log.errorf("Failed to audit event from %s: %s",
                                topic, t.getMessage());
                        span.recordException(t);
                    }
                    record.ack();
                    span.end();
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
        Span span = startReceiveSpan(record, topic);
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
                        span.recordException(t);
                    }
                    record.ack();
                    span.end();
                })
                .onFailure().recoverWithNull();
    }

    /**
     * Manual trace propagation - counterpart to Spring's Micrometer
     * Tracer/Propagator on the JVM services. Extracts whatever trace
     * context arrived on this record's Kafka headers (B3 single-header
     * "b3", written either by Brave directly or promoted from a Postgres
     * outbox column via the Debezium EventRouter SMT - see
     * debezium/register.sh) and starts this consumer's span as its child,
     * so it shows up in the same Zipkin trace instead of a new one.
     *
     * Not wrapped in Context.makeCurrent(): the Uni pipeline this feeds
     * into hops threads (Vert.x event loop / worker), and an OTel Scope
     * must be closed on the same thread that opened it - crossing that
     * boundary here would risk mismatched open/close pairs. The span
     * itself (start/end) has no such constraint, so that's all this does;
     * child spans from Elasticsearch calls nesting under it correctly is
     * not required for one-trace-per-flow visibility in Zipkin.
     */
    private Span startReceiveSpan(KafkaRecord<String, String> record, String topic) {
        Headers headers = record.getMetadata(IncomingKafkaRecordMetadata.class)
                .map(IncomingKafkaRecordMetadata::getHeaders)
                .orElse(new RecordHeaders());

        Context extracted = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), headers, KAFKA_HEADER_GETTER);

        return GlobalOpenTelemetry.getTracer("nexus-audit-write-native")
                .spanBuilder(topic + " receive")
                .setParent(extracted)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();
    }

    private static final TextMapGetter<Headers> KAFKA_HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            List<String> keys = new ArrayList<>();
            carrier.forEach(h -> keys.add(h.key()));
            return keys;
        }

        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null) return null;
            Header header = carrier.lastHeader(key);
            // header.value() is null (not just header itself) whenever the
            // source outbox column was SQL NULL and Debezium's EventRouter
            // SMT promoted it verbatim into the "b3" header - NULL, not
            // "header absent". OutboxEntry.attachTraceContext is a
            // documented no-op when there's no active span at write time,
            // so this is common, not an edge case.
            if (header == null || header.value() == null) return null;
            return new String(header.value(), StandardCharsets.UTF_8);
        }
    };

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
                // Matched on Throwable, not the narrower ElasticsearchException
                // type: the async client can wrap the real error (e.g. in a
                // CompletionException/TransportException), which would fail
                // an exact-type onFailure(ElasticsearchException.class) match
                // and let a 409 fall through as an unhandled failure -
                // exactly what was showing up as "hundreds of
                // version_conflict_engine_exception errors" instead of the
                // idempotent-skip this was meant to produce. Walking the
                // cause chain for the conflict signature is robust to
                // whatever wrapper type is actually thrown.
                .onFailure(Throwable.class)
                .recoverWithUni(e -> {
                    if (isVersionConflict(e)) {
                        log.debugf("Idempotent replay (version conflict, " +
                                "already indexed): %s", event.eventId());
                        return Uni.createFrom().voidItem();
                    }
                    return Uni.createFrom().failure(e);
                });
    }

    private boolean isVersionConflict(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("version_conflict")
                    || msg.contains("409"))) {
                return true;
            }
            if (cur.getCause() == cur) break; // guard against self-referencing cause
        }
        return false;
    }
}