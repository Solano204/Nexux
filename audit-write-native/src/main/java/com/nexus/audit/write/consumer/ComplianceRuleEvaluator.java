package com.nexus.audit.write.consumer;

import com.nexus.audit.write.model.AuditEvent;
import com.nexus.audit.write.model.ComplianceAlert;
import io.quarkus.redis.client.reactive.ReactiveRedisClient;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Compliance Rule Evaluator — real-time rule assessment on write path.
 *
 * Rules evaluated:
 * 1. Transaction velocity spike (5+ transactions in 5 minutes)
 * 2. Large transaction (> MXN 10,000)
 * 3. High fraud score event (> 70)
 * 4. CRITICAL severity event (account freeze, document fraud)
 * 5. Potential structuring (multiple sub-threshold transactions)
 *
 * Uses Redis sliding window counters for velocity detection.
 * Creates compliance alerts in MongoDB for compliance team review.
 */
@ApplicationScoped
@RegisterForReflection
public class ComplianceRuleEvaluator {

    private static final Logger log =
            Logger.getLogger(ComplianceRuleEvaluator.class);

    private static final BigDecimal LARGE_AMOUNT =
            new BigDecimal("10000");
    private static final BigDecimal STRUCTURING_LOWER =
            new BigDecimal("7000");

    @Inject
    ReactiveRedisClient redis;

    @Inject
    com.mongodb.reactivestreams.client.MongoClient mongoClient;

    public Uni<Void> evaluate(AuditEvent event) {
        return Uni.combine().all().unis(
                checkVelocity(event),
                checkLargeTransaction(event),
                checkFraudScore(event),
                checkCriticalSeverity(event),
                checkStructuring(event)
        ).discardItems();
    }

    private Uni<Void> checkVelocity(AuditEvent event) {
        if (event.userId() == null || !event.isFinancialEvent())
            return Uni.createFrom().voidItem();

        String key = "audit:velocity:txn:" + event.userId() + ":" +
                (System.currentTimeMillis() / (5 * 60 * 1000));

        return redis.incr(key)
                .flatMap(count -> {
                    redis.expire(key, "360"); // 6 minute TTL
                    if (count.toLong() >= 5) {
                        return createAlert(ComplianceAlert.builder()
                                .alertId(UUID.randomUUID().toString())
                                .alertType("VELOCITY_ANOMALY")
                                .severity(count.toLong() >= 10
                                        ? "CRITICAL" : "HIGH")
                                .userId(event.userId())
                                .triggeringEventId(event.eventId())
                                .description(count.toLong() +
                                        " transactions in 5 minutes")
                                .regulatoryReference(
                                        "CNBV Transaction Monitoring")
                                .sarConsideration(count.toLong() >= 10)
                                .generatedAt(Instant.now())
                                .build());
                    }
                    return Uni.createFrom().voidItem();
                })
                .onFailure().recoverWithNull();
    }

    private Uni<Void> checkLargeTransaction(AuditEvent event) {
        if (!event.isFinancialEvent()) return Uni.createFrom().voidItem();

        Object amountObj = event.payload() != null
                ? event.payload().get("amount") : null;

        if (amountObj == null) return Uni.createFrom().voidItem();

        try {
            BigDecimal amount = new BigDecimal(amountObj.toString());
            if (amount.compareTo(LARGE_AMOUNT) > 0) {
                return createAlert(ComplianceAlert.builder()
                        .alertId(UUID.randomUUID().toString())
                        .alertType("LARGE_TRANSACTION")
                        .severity("WARNING")
                        .userId(event.userId())
                        .triggeringEventId(event.eventId())
                        .description("Transaction of " +
                                event.payload().get("currency") + " " +
                                amount.toPlainString() +
                                " exceeds MXN 10,000 threshold")
                        .regulatoryReference(
                                "CNBV Anti-Money Laundering Guidelines")
                        .sarConsideration(false)
                        .generatedAt(Instant.now())
                        .build());
            }
        } catch (NumberFormatException ignored) {}

        return Uni.createFrom().voidItem();
    }

    private Uni<Void> checkFraudScore(AuditEvent event) {
        Object scoreObj = event.payload() != null
                ? event.payload().get("fraudScore") : null;
        if (scoreObj == null) return Uni.createFrom().voidItem();

        try {
            double score = Double.parseDouble(scoreObj.toString());
            if (score > 70) {
                return createAlert(ComplianceAlert.builder()
                        .alertId(UUID.randomUUID().toString())
                        .alertType("HIGH_FRAUD_SCORE")
                        .severity(score > 90 ? "CRITICAL" : "HIGH")
                        .userId(event.userId())
                        .triggeringEventId(event.eventId())
                        .description("Fraud score " + score +
                                " exceeds threshold")
                        .sarConsideration(score > 90)
                        .generatedAt(Instant.now())
                        .build());
            }
        } catch (NumberFormatException ignored) {}

        return Uni.createFrom().voidItem();
    }

    private Uni<Void> checkCriticalSeverity(AuditEvent event) {
        if ("CRITICAL".equals(event.severity()) &&
                event.requiresSarReview()) {
            return createAlert(ComplianceAlert.builder()
                    .alertId(UUID.randomUUID().toString())
                    .alertType("CRITICAL_EVENT_" + event.eventType())
                    .severity("CRITICAL")
                    .userId(event.userId())
                    .triggeringEventId(event.eventId())
                    .description("Critical event: " + event.eventType())
                    .sarConsideration(true)
                    .generatedAt(Instant.now())
                    .build());
        }
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> checkStructuring(AuditEvent event) {
        if (!event.isFinancialEvent() || event.userId() == null)
            return Uni.createFrom().voidItem();

        Object amountObj = event.payload() != null
                ? event.payload().get("amount") : null;
        if (amountObj == null) return Uni.createFrom().voidItem();

        try {
            BigDecimal amount = new BigDecimal(amountObj.toString());
            if (amount.compareTo(STRUCTURING_LOWER) >= 0 &&
                    amount.compareTo(LARGE_AMOUNT) < 0) {

                String key = "audit:structuring:" +
                        event.userId() + ":" +
                        (System.currentTimeMillis() /
                                (7 * 24 * 60 * 60 * 1000L));

                return redis.incr(key)
                        .flatMap(count -> {
                            redis.expire(key, "604800"); // 7 days TTL
                            if (count.toLong() >= 3) {
                                return createAlert(
                                        ComplianceAlert.builder()
                                                .alertId(UUID.randomUUID()
                                                        .toString())
                                                .alertType(
                                                        "POTENTIAL_STRUCTURING")
                                                .severity("CRITICAL")
                                                .userId(event.userId())
                                                .triggeringEventId(event.eventId())
                                                .description(count.toLong() +
                                                        " transactions between " +
                                                        "MXN 7,000-9,999 in 7 days")
                                                .regulatoryReference(
                                                        "CNBV AML Section 6.1")
                                                .sarConsideration(true)
                                                .generatedAt(Instant.now())
                                                .build());
                            }
                            return Uni.createFrom().voidItem();
                        });
            }
        } catch (NumberFormatException ignored) {}

        return Uni.createFrom().voidItem();
    }

    private Uni<Void> createAlert(ComplianceAlert alert) {
        Document doc = new Document()
                .append("_id", alert.alertId())
                .append("alertType", alert.alertType())
                .append("severity", alert.severity())
                .append("userId", alert.userId())
                .append("triggeringEventId", alert.triggeringEventId())
                .append("description", alert.description())
                .append("regulatoryReference", alert.regulatoryReference())
                .append("sarConsideration", alert.sarConsideration())
                .append("generatedAt", alert.generatedAt().toString())
                .append("reviewed", false);

        return Uni.createFrom().publisher(
                        mongoClient.getDatabase("nexus_audit")
                                .getCollection("compliance_alerts")
                                .insertOne(doc))
                .replaceWithVoid()
                .onFailure().invoke(t ->
                        log.warnf("Failed to create compliance alert: %s",
                                t.getMessage()))
                .onFailure().recoverWithNull();
    }
}