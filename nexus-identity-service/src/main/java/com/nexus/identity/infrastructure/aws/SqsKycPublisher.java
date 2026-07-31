package com.nexus.identity.infrastructure.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SQS KYC Publisher — Triggers Rekognition Lambda.
 *
 * Direct SQS publish (not via Kafka bridge) because the
 * Rekognition Lambda is already listening to this SQS queue
 * and adding Kafka→SQS bridge adds unnecessary complexity.
 *
 * The Lambda reads the S3 path and triggers Rekognition analysis.
 * Results published to nexus-kyc-rekognition-results SQS queue
 * → consumed by nexus-ai-kyc-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqsKycPublisher {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    @Value("${nexus.aws.kyc-queue-url:}")
    private String kycQueueUrl;

    public void publishKycDocumentForAnalysis(UUID userId,
                                              UUID verificationId,
                                              String s3Path,
                                              String documentType) {

        if (kycQueueUrl == null || kycQueueUrl.isBlank()) {
            log.warn("KYC SQS queue URL not configured, skipping publish");
            return;
        }

        Observation obs = Observation.createNotStarted(
                "sqs.publish.kyc", observationRegistry).start();

        try {
            Map<String, Object> messageBody = Map.of(
                    "userId", userId.toString(),
                    "verificationId", verificationId.toString(),
                    "s3Path", s3Path,
                    "documentType", documentType,
                    "publishedAt", java.time.Instant.now().toString()
            );

            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            messageAttributes.put("userId", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(userId.toString())
                    .build());
            messageAttributes.put("documentType", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(documentType)
                    .build());

            // SQS isn't auto-instrumented by Micrometer Tracing (unlike Kafka/HTTP),
            // so the trace context has to be injected into message attributes by hand
            // or the consumer side starts a disconnected trace.
            if (tracer.currentTraceContext().context() != null) {
                propagator.inject(tracer.currentTraceContext().context(), messageAttributes,
                        (carrier, key, value) -> carrier.put(key, MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(value)
                                .build()));
            }

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(kycQueueUrl)
                    .messageBody(objectMapper.writeValueAsString(messageBody))
                    .messageAttributes(messageAttributes)
                    .build();

            sqsClient.sendMessage(request);

            obs.event(Observation.Event.of("sqs.publish.success"));
            log.info("KYC document published to SQS: userId={} " +
                    "verificationId={}", userId, verificationId);

        } catch (Exception e) {
            obs.error(e);
            log.error("SQS publish failed: userId={} error={}",
                    userId, e.getMessage());
            throw new RuntimeException("Failed to queue KYC analysis", e);
        } finally {
            obs.stop();
        }
    }
}