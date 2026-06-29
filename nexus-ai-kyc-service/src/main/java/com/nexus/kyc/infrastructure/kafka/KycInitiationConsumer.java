package com.nexus.kyc.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.kyc.application.KycVerificationService;
import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import com.nexus.kyc.infrastructure.storage.DocumentStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KycInitiationConsumer {

    private final KycVerificationService verificationService;
    private final DocumentStorageService documentStorageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${nexus.identity.internal-url:http://localhost:8010}")
    private String identityServiceUrl;

    @KafkaListener(topics = "identity.kyc", groupId = "nexus-ai-kyc-service")
    public void onKycInitiated(String message) {
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

            KycDocumentMongoDB result = verificationService.verify(
                    request, imageBytes, mimeType, verificationId);

            callbackIdentityService(userId, verificationId, result);

        } catch (Exception e) {
            log.error("KYC initiation consumer failed: userId={} error={}", userId, e.getMessage(), e);
            throw new RuntimeException("KYC initiation processing failed", e);
        }
    }

    private void callbackIdentityService(String userId, String verificationId, KycDocumentMongoDB doc) {
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
            body.put("verificationId",     verificationId);
            body.put("approved",           doc.getStatus() == KycStatus.APPROVED);
            body.put("extractedData",      extractedData);
            body.put("verificationDecision", decisionMap);
            body.put("failureReasons",     failureReasons);

            ResponseEntity<Void> response = restTemplate.postForEntity(url, body, Void.class);
            log.info("KYC result sent to identity service: userId={} status={} http={}",
                    userId, doc.getStatus(), response.getStatusCode());

        } catch (Exception e) {
            log.error("Failed to callback identity service: userId={} error={}", userId, e.getMessage());
        }
    }
}
