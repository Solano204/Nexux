package com.nexus.identity.infrastructure.aws;

import com.nexus.identity.application.command.DocumentUploadException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Map;
import java.util.UUID;

/**
 * S3 Document Uploader — Async document storage for KYC.
 *
 * Used by Structured Concurrency Task B in KYC initiation.
 * Stores identity documents encrypted at rest (KMS key on bucket).
 * Path format: kyc/{userId}/{verificationId}/{documentType}.jpg
 *
 * Tags set on S3 object are read by Rekognition Lambda
 * to associate results with the correct user/verification.
 */
@Slf4j
@Component
public class S3DocumentUploader {

    private final S3Client s3Client;
    private final ObservationRegistry observationRegistry;

    @Value("${nexus.aws.kyc-bucket:nexus-kyc-documents}")
    private String kycBucket;

    public S3DocumentUploader(S3Client s3Client,
                              ObservationRegistry observationRegistry) {
        this.s3Client = s3Client;
        this.observationRegistry = observationRegistry;
    }

    /**
     * Uploads a KYC document to S3.
     * Called within Structured Concurrency scope — throws on failure.
     *
     * @return The S3 key (path) where the document was stored
     */
    public String uploadKycDocument(UUID userId, UUID verificationId,
                                    String documentType,
                                    MultipartFile file) throws Exception {

        Observation obs = Observation.createNotStarted(
                "s3.upload.kyc", observationRegistry).start();

        String s3Key = String.format("kyc/%s/%s/%s.jpg",
                userId, verificationId, documentType.toLowerCase());

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(kycBucket)
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .tagging(buildTagging(userId, verificationId, documentType))
                    .build();

            s3Client.putObject(request,
                    RequestBody.fromInputStream(file.getInputStream(),
                            file.getSize()));

            obs.event(Observation.Event.of("s3.upload.success"));
            log.info("KYC document uploaded: userId={} key={}",
                    userId, s3Key);

            return s3Key;

        } catch (Exception e) {
            obs.error(e);
            log.error("S3 upload failed: userId={} error={}",
                    userId, e.getMessage());
            throw new DocumentUploadException(
                    "Failed to upload KYC document. Please try again.", e);
        } finally {
            obs.stop();
        }
    }

    private String buildTagging(UUID userId, UUID verificationId,
                                String documentType) {
        return String.format(
                "userId=%s&verificationId=%s&documentType=%s&uploadedAt=%s",
                userId, verificationId, documentType,
                java.time.Instant.now().toString()
        );
    }
}