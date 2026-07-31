package com.nexus.kyc.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.kyc.application.KycVerificationService;
import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.infrastructure.storage.DocumentStorageService;
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KycInitiationConsumer {

    private final KycVerificationService verificationService;
    private final DocumentStorageService documentStorageService;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    // The platform-wide default ack-mode (nexus-platform-config:
    // spring.kafka.listener.ack-mode=manual) requires the listener to
    // accept an Acknowledgment and call it explicitly - this method
    // previously didn't, so this consumer's offsets were never committed,
    // even on success. Every restart/rebalance replayed from the last
    // (very old) committed offset, including messages whose S3 objects may
    // have since been cleaned up - the most likely explanation for a
    // NoSuchKeyException on what was, at publish time, a confirmed-good
    // key (upload is synchronous S3Client.putObject(), confirmed complete
    // before the Kafka message is published - see S3DocumentUploader /
    // StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow() in
    // UserCommandService.initiateKyc()). Bucket/key naming was compared
    // between S3DocumentUploader and DocumentStorageService and both
    // resolve from the same KYC_S3_BUCKET env var with the same key
    // unmodified, so that is not the mismatch.
    @KafkaListener(topics = "identity.kyc", groupId = "nexus-ai-kyc-service")
    public void onKycInitiated(ConsumerRecord<String, String> record,
                               Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "nexus-ai-kyc-service", "identity.kyc receive");
        try (Tracer.SpanInScope ignoredScope = tracer.withSpan(span)) {
            onKycInitiatedTraced(message, ack);
        } finally {
            span.end();
        }
    }

    private void onKycInitiatedTraced(String message, Acknowledgment ack) {
        String userId = null;
        try {
            JsonNode event = objectMapper.readTree(message);

            userId           = event.get("userId").asText();
            String verificationId = event.get("verificationId").asText();
            String s3Path         = event.get("s3Path").asText();
            String documentType   = event.get("documentType").asText();
            String fullName       = event.path("fullName").asText("");
            String dateOfBirth    = event.path("dateOfBirth").asText("");
            String documentNumber = event.path("documentNumber").asText("");
            String nationality    = event.path("nationality").asText(null);
            String language       = event.path("language").asText("es");
            String mimeType       = event.path("mimeType").asText("image/jpeg");

            log.info("KYC initiation received: userId={} verificationId={}", userId, verificationId);

            byte[] imageBytes = documentStorageService.downloadFromS3(s3Path);

            KycVerificationRequest request = new KycVerificationRequest(
                    userId, fullName, dateOfBirth, documentNumber,
                    DocumentType.valueOf(documentType),
                    nationality, language
            );

            // verificationService.verify() -> persistAndPublish() already
            // publishes the result to identity-service reliably (Outbox +
            // Debezium, topic identity.kyc.result) - see
            // KycResultOutboxPublisher. This used to also call a
            // callbackIdentityService() HTTP POST here with no retry;
            // removed as part of CHANGES-BESTPRACTICES/
            // 08_EVENT_DESIGN_CHANGES.md Section 6, since it duplicated
            // (unreliably) what the outbox publish already does atomically.
            // verificationId is the durable ID identity-service tracks this
            // verification under - must be used as-is (not just as sagaId)
            // so the eventual result can be correlated back to it. Was
            // previously only passed as sagaId, which doVerify() never used
            // for the persisted document's own verificationId - it minted a
            // fresh random one instead, silently breaking that correlation.
            verificationService.verify(request, imageBytes, mimeType, verificationId, verificationId);

            ack.acknowledge();

        } catch (Exception e) {
            log.error("KYC initiation consumer failed: userId={} error={}", userId, e.getMessage(), e);
            // Rethrown (not acked) so the container's error handler applies
            // its bounded backoff, then routes to the DLQ once exhausted -
            // see KafkaConfig.kafkaListenerContainerFactory(). Do not ack() here.
            throw new RuntimeException("KYC initiation processing failed", e);
        }
    }
}
