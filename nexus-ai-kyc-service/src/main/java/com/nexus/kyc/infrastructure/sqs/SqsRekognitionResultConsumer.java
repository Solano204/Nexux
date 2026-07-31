package com.nexus.kyc.infrastructure.sqs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.kyc.application.KycVerificationService;
import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.infrastructure.kafka.KycResultOutboxPublisher;
import com.nexus.kyc.infrastructure.storage.DocumentStorageService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls nexus-kyc-rekognition-results SQS queue produced by the KYC Rekognition Lambda.
 * Message body is a JSON Rekognition result; this consumer maps it back to a KYC decision
 * and publishes it to identity-service via KycResultOutboxPublisher.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqsRekognitionResultConsumer {

    private final SqsClient sqsClient;
    private final KycVerificationService verificationService;
    private final DocumentStorageService documentStorageService;
    private final KycResultOutboxPublisher resultPublisher;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    private static final Propagator.Getter<Map<String, MessageAttributeValue>> ATTRIBUTE_GETTER =
            (carrier, key) -> {
                MessageAttributeValue value = carrier.get(key);
                return value != null ? value.stringValue() : null;
            };

    @Value("${nexus.aws.kyc-rekognition-results-queue-url:}")
    private String queueUrl;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        if (queueUrl.isBlank()) {
            log.info("KYC_REKOGNITION_RESULTS_QUEUE_URL not set — SQS consumer disabled");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sqs-kyc-rekognition-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 5, 5, TimeUnit.SECONDS);
        log.info("SQS Rekognition results consumer started: queue={}", queueUrl);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void poll() {
        try {
            ReceiveMessageResponse response = sqsClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(10)
                            .waitTimeSeconds(20)
                            .messageAttributeNames("All")
                            .build());

            for (Message message : response.messages()) {
                // SQS doesn't auto-propagate trace context like Kafka/HTTP do, so
                // the identity-service publisher injects it into message attributes
                // by hand (see SqsKycPublisher) and it's extracted back out here -
                // without this, every message starts a brand-new, disconnected trace.
                Span span = propagator.extract(message.messageAttributes(), ATTRIBUTE_GETTER)
                        .name("sqs.process.kyc-rekognition-result")
                        .kind(Span.Kind.CONSUMER)
                        .start();
                try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                    processMessage(message);
                    sqsClient.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                } catch (Exception e) {
                    span.error(e);
                    log.error("Failed to process rekognition result message={}: {}",
                            message.messageId(), e.getMessage(), e);
                } finally {
                    span.end();
                }
            }
        } catch (Exception e) {
            log.error("SQS poll error: {}", e.getMessage(), e);
        }
    }

    private void processMessage(Message message) throws Exception {
        JsonNode body = objectMapper.readTree(message.body());

        String userId = getAttr(message, "userId", body.path("userId").asText(""));
        String verificationId = getAttr(message, "verificationId", body.path("verificationId").asText(""));
        String processingStatus = getAttr(message, "processingStatus", body.path("processingStatus").asText("FAILED"));

        log.info("Rekognition result received: userId={} verificationId={} status={}",
                userId, verificationId, processingStatus);

        if (userId.isBlank() || verificationId.isBlank()) {
            log.warn("Missing userId or verificationId in rekognition result — skipping");
            return;
        }

        if (!"QUALITY_PASSED".equals(processingStatus)) {
            // Rekognition itself failed before any AI verification ran -
            // no KycDocumentMongoDB to persist, so this bypasses
            // KycVerificationService entirely and publishes the failure
            // signal directly. See KycResultOutboxPublisher.publishFailure.
            resultPublisher.publishFailure(userId, verificationId,
                    "Rekognition processing failed: " + processingStatus);
            return;
        }

        String s3Key = body.path("s3Key").asText("");
        String mimeType = body.path("mimeType").asText("image/jpeg");
        String documentType = body.path("documentType").asText("NATIONAL_ID");
        String fullName = body.path("extractedName").asText("");
        String dateOfBirth = body.path("extractedDob").asText("");

        byte[] imageBytes = s3Key.isBlank()
                ? new byte[0]
                : documentStorageService.downloadFromS3(s3Key);

        KycVerificationRequest request = new KycVerificationRequest(
                userId, fullName, dateOfBirth, "",
                DocumentType.valueOf(documentType.toUpperCase().replace("-", "_")),
                null, "es");

        // verificationService.verify() -> persistAndPublish() already
        // publishes the result to identity-service reliably (Outbox +
        // Debezium, topic identity.kyc.result) - see
        // KycResultOutboxPublisher. This used to also call a
        // callbackIdentityService() HTTP POST here with no retry (its own
        // separate copy of the same logic KycInitiationConsumer had);
        // removed as part of CHANGES-BESTPRACTICES/
        // 08_EVENT_DESIGN_CHANGES.md Section 6.
        // verificationId must be used as-is (not just as sagaId) so this
        // result correlates back to the verification identity-service
        // originally requested - see KycInitiationConsumer for the same fix.
        verificationService.verify(request, imageBytes, mimeType, verificationId, verificationId);
    }

    private String getAttr(Message message, String key, String fallback) {
        MessageAttributeValue attr = message.messageAttributes().get(key);
        return (attr != null && attr.stringValue() != null) ? attr.stringValue() : fallback;
    }
}
