package com.nexus.kyc.infrastructure.sqs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.kyc.application.KycVerificationService;
import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import com.nexus.kyc.infrastructure.storage.DocumentStorageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls nexus-kyc-rekognition-results SQS queue produced by the KYC Rekognition Lambda.
 * Message body is a JSON Rekognition result; this consumer maps it back to a KYC decision
 * and calls identity-service with the result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqsRekognitionResultConsumer {

    private final SqsClient sqsClient;
    private final KycVerificationService verificationService;
    private final DocumentStorageService documentStorageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${nexus.aws.kyc-rekognition-results-queue-url:}")
    private String queueUrl;

    @Value("${nexus.identity.internal-url:http://localhost:8083}")
    private String identityServiceUrl;

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
                try {
                    processMessage(message);
                    sqsClient.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                } catch (Exception e) {
                    log.error("Failed to process rekognition result message={}: {}",
                            message.messageId(), e.getMessage(), e);
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
            callbackWithFailure(userId, verificationId,
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

        KycDocumentMongoDB result = verificationService.verify(
                request, imageBytes, mimeType, verificationId);

        callbackIdentityService(userId, verificationId, result);
    }

    private void callbackIdentityService(String userId, String verificationId,
                                         KycDocumentMongoDB doc) {
        try {
            String url = identityServiceUrl + "/internal/v1/users/" + userId + "/kyc/result";

            Map<String, Object> extractedData = new HashMap<>();
            if (doc.getExtractedData() != null) {
                extractedData = objectMapper.convertValue(doc.getExtractedData(), Map.class);
            }

            Map<String, Object> decisionMap = new HashMap<>();
            if (doc.getDecision() != null) {
                decisionMap.put("status", doc.getDecision().status().name());
                decisionMap.put("confidenceScore", doc.getDecision().confidenceScore());
                decisionMap.put("userFacingMessage", doc.getDecision().userFacingRejectionMessage());
            }

            List<String> failureReasons = List.of();
            if (doc.getDecision() != null && doc.getDecision().rejectionReasons() != null) {
                failureReasons = doc.getDecision().rejectionReasons()
                        .stream().map(Enum::name).toList();
            }

            Map<String, Object> body = new HashMap<>();
            body.put("verificationId", verificationId);
            body.put("approved", doc.getStatus() == KycStatus.APPROVED);
            body.put("extractedData", extractedData);
            body.put("verificationDecision", decisionMap);
            body.put("failureReasons", failureReasons);

            ResponseEntity<Void> resp = restTemplate.postForEntity(url, body, Void.class);
            log.info("KYC result (from Rekognition) sent to identity: userId={} status={} http={}",
                    userId, doc.getStatus(), resp.getStatusCode());

        } catch (Exception e) {
            log.error("Failed to callback identity service (Rekognition path): userId={} error={}",
                    userId, e.getMessage());
        }
    }

    private void callbackWithFailure(String userId, String verificationId, String reason) {
        try {
            String url = identityServiceUrl + "/internal/v1/users/" + userId + "/kyc/result";
            Map<String, Object> body = Map.of(
                    "verificationId", verificationId,
                    "approved", false,
                    "failureReasons", List.of("REKOGNITION_PROCESSING_FAILED"),
                    "verificationDecision", Map.of("status", "REJECTED", "userFacingMessage", reason)
            );
            restTemplate.postForEntity(url, body, Void.class);
        } catch (Exception e) {
            log.error("Failed to send failure callback to identity: userId={} error={}",
                    userId, e.getMessage());
        }
    }

    private String getAttr(Message message, String key, String fallback) {
        MessageAttributeValue attr = message.messageAttributes().get(key);
        return (attr != null && attr.stringValue() != null) ? attr.stringValue() : fallback;
    }
}
