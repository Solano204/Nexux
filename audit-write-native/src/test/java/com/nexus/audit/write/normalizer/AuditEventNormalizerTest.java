package com.nexus.audit.write.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.audit.write.model.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuditEventNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new AuditEventNormalizer();
    }

    private JsonNode json(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    void derivesEventTypeFromTopicWhenFieldMissing() throws Exception {
        JsonNode raw = json("{\"userId\":\"user-1\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.eventType()).isEqualTo("TRANSACTION_COMPLETED");
        assertThat(event.sourceService()).isEqualTo("nexus-transaction-service");
    }

    @Test
    void prefersExplicitEventTypeFieldOverTopicInference() throws Exception {
        JsonNode raw = json("{\"eventType\":\"CUSTOM_EVENT\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.eventType()).isEqualTo("CUSTOM_EVENT");
    }

    @Test
    void fallsBackToReplyTypeThenCommandType() throws Exception {
        JsonNode replyEvent = json("{\"replyType\":\"LedgerPostedReply\"}");
        JsonNode commandEvent = json("{\"commandType\":\"ReserveBalanceCommand\"}");

        assertThat(normalizer.normalize(replyEvent, "saga.replies", 1L).eventType())
                .isEqualTo("LedgerPostedReply");
        assertThat(normalizer.normalize(commandEvent, "saga.commands", 1L).eventType())
                .isEqualTo("ReserveBalanceCommand");
    }

    @Test
    void categorizesFinancialEventsCorrectly() throws Exception {
        AuditEvent event = normalizer.normalize(json("{}"), "ledger.posted", 1L);

        assertThat(event.category()).isEqualTo("FINANCIAL");
        assertThat(event.isFinancialEvent()).isTrue();
        assertThat(event.isRegulatoryRequired()).isTrue();
    }

    @Test
    void categorizesSecurityEventsCorrectly() throws Exception {
        AuditEvent event = normalizer.normalize(json("{}"), "fraud.flagged", 1L);

        assertThat(event.category()).isEqualTo("SECURITY");
    }

    @Test
    void categorizesComplianceEventsCorrectly() throws Exception {
        AuditEvent event = normalizer.normalize(json("{}"), "identity.verified", 1L);

        assertThat(event.category()).isEqualTo("COMPLIANCE");
        assertThat(event.isSensitiveData()).isTrue();
    }

    @Test
    void classifiesCriticalSeverityForHighFraudScore() throws Exception {
        JsonNode raw = json("{\"payload\":{\"fraudScore\":92}}");

        AuditEvent event = normalizer.normalize(raw, "fraud.result", 1L);

        assertThat(event.severity()).isEqualTo("CRITICAL");
        assertThat(event.requiresSarReview()).isTrue();
    }

    @Test
    void classifiesWarningSeverityForModerateFraudScore() throws Exception {
        JsonNode raw = json("{\"payload\":{\"fraudScore\":65}}");

        AuditEvent event = normalizer.normalize(raw, "fraud.result", 1L);

        assertThat(event.severity()).isEqualTo("WARNING");
        assertThat(event.requiresSarReview()).isFalse();
    }

    @Test
    void classifiesWarningSeverityForLargeAmountEvenWithoutFraudScore() throws Exception {
        JsonNode raw = json("{\"amount\":\"15000.00\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.severity()).isEqualTo("WARNING");
    }

    @Test
    void classifiesInfoSeverityByDefault() throws Exception {
        JsonNode raw = json("{\"amount\":\"50.00\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.severity()).isEqualTo("INFO");
    }

    @Test
    void classifiesCriticalSeverityForFrozenEventTypeRegardlessOfScore() throws Exception {
        AuditEvent event = normalizer.normalize(json("{}"), "account.frozen", 1L);

        assertThat(event.severity()).isEqualTo("CRITICAL");
    }

    @Test
    void extractsUserIdFromFirstMatchingField() throws Exception {
        JsonNode raw = json("{\"sourceUserId\":\"user-42\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.userId()).isEqualTo("user-42");
    }

    @Test
    void extractsUserIdFromNestedPayloadWhenTopLevelMissing() throws Exception {
        JsonNode raw = json("{\"payload\":{\"userId\":\"nested-user\"}}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.userId()).isEqualTo("nested-user");
    }

    @Test
    void extractsOrGeneratesEventIdPreferringEventIdField() throws Exception {
        JsonNode raw = json("{\"eventId\":\"evt-123\",\"transactionId\":\"txn-456\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.eventId()).isEqualTo("evt-123");
    }

    @Test
    void generatesRandomEventIdWhenNoIdentifierFieldPresent() throws Exception {
        AuditEvent event = normalizer.normalize(json("{}"), "transactions.completed", 1L);

        assertThat(event.eventId()).isNotBlank();
        assertThat(java.util.UUID.fromString(event.eventId())).isNotNull();
    }

    @Test
    void parsesExplicitTimestampWhenPresent() throws Exception {
        JsonNode raw = json("{\"completedAt\":\"2026-01-15T10:30:00Z\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.eventTimestamp()).isEqualTo(java.time.Instant.parse("2026-01-15T10:30:00Z"));
    }

    @Test
    void fallsBackToNowWhenTimestampUnparsable() throws Exception {
        JsonNode raw = json("{\"completedAt\":\"not-a-date\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.eventTimestamp()).isCloseTo(java.time.Instant.now(), within500Millis());
    }

    @Test
    void extractsTransactionResourceIdForTransactionEvents() throws Exception {
        JsonNode raw = json("{\"transactionId\":\"txn-789\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.resourceType()).isEqualTo("TRANSACTION");
        assertThat(event.resourceId()).isEqualTo("txn-789");
    }

    @Test
    void requiresSarReviewForFraudHighSeverityEventType() throws Exception {
        AuditEvent event = normalizer.normalize(json("{}"), "fraud.flagged", 1L);

        assertThat(event.requiresSarReview()).isTrue();
    }

    @Test
    void unknownTopicMapsToUnknownEventTypeAndSourceService() throws Exception {
        AuditEvent event = normalizer.normalize(json("{}"), "some.unmapped.topic", 1L);

        assertThat(event.eventType()).isEqualTo("UNKNOWN_EVENT_SOME_UNMAPPED_TOPIC");
        assertThat(event.sourceService()).isEqualTo("unknown");
        assertThat(event.category()).isEqualTo("GENERAL");
    }

    @Test
    void payloadIncludesScalarFieldsAsTextAndNestedFieldsAsJsonString() throws Exception {
        JsonNode raw = json("{\"amount\":\"100.00\",\"payload\":{\"nested\":\"value\"}}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.payload()).containsEntry("amount", "100.00");
        assertThat(event.payload().get("payload")).asString().contains("nested");
    }

    @Test
    void malformedAmountStringDoesNotCrashNormalization() throws Exception {
        JsonNode raw = json("{\"amount\":\"not-a-number\"}");

        AuditEvent event = normalizer.normalize(raw, "transactions.completed", 1L);

        assertThat(event.severity()).isEqualTo("INFO");
    }

    private org.assertj.core.data.TemporalUnitOffset within500Millis() {
        return org.assertj.core.api.Assertions.within(500, java.time.temporal.ChronoUnit.MILLIS);
    }
}
