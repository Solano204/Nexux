package com.nexus.kyc.infrastructure.storage;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

@Slf4j
@Service
public class DocumentStorageService {

    private final S3Client s3Client;
    private final GridFsTemplate gridFsTemplate;
    private final ObservationRegistry observationRegistry;

    @Value("${nexus.kyc.s3.bucket:nexus-kyc-documents}")
    private String s3Bucket;

    @Value("${nexus.kyc.encryption.enabled:false}")
    private boolean encryptionEnabled;

    public DocumentStorageService(S3Client s3Client,
                                  GridFsTemplate gridFsTemplate,
                                  ObservationRegistry observationRegistry) {
        this.s3Client = s3Client;
        this.gridFsTemplate = gridFsTemplate;
        this.observationRegistry = observationRegistry;
    }

    public byte[] downloadFromS3(String s3Path) {
        Observation obs = Observation.createNotStarted(
                "kyc.s3.download", observationRegistry).start();
        try (Observation.Scope scope = obs.openScope()) {
            byte[] bytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(s3Bucket)
                            .key(s3Path)
                            .build()
            ).asByteArray();
            obs.event(Observation.Event.of("s3.download.complete"));
            log.info("Document downloaded: path={} size={}KB",
                    s3Path, bytes.length / 1024);
            return bytes;
        } catch (Exception e) {
            obs.error(e);
            throw new RuntimeException("S3 download failed: " + s3Path, e);
        } finally {
            obs.stop();
        }
    }

    /**
     * Store encrypted document in MongoDB GridFS.
     * Returns GridFS file ID for reference in KycDocumentMongoDB.
     */
    public String storeEncrypted(byte[] documentBytes,
                                 String userId,
                                 String verificationId) {
        Observation obs = Observation.createNotStarted(
                "kyc.document.gridfs.store", observationRegistry).start();
        try (Observation.Scope scope = obs.openScope()) {
            byte[] toStore = encryptionEnabled
                    ? encrypt(documentBytes)
                    : documentBytes;

            String filename = "kyc/%s/%s.encrypted"
                    .formatted(userId, verificationId);
            String sha256 = computeSha256(documentBytes);

            var metadata = new org.bson.Document();
            metadata.put("userId", userId);
            metadata.put("verificationId", verificationId);
            metadata.put("encrypted", encryptionEnabled);
            metadata.put("sha256", sha256);
            metadata.put("originalSizeBytes", documentBytes.length);
            metadata.put("retentionYears", 7);

            String gridFsId = gridFsTemplate.store(
                    new ByteArrayInputStream(toStore),
                    filename,
                    "application/octet-stream",
                    metadata
            ).toString();

            obs.event(Observation.Event.of("gridfs.store.complete"));
            log.info("GridFS stored: id={} user={} encrypted={}",
                    gridFsId, userId, encryptionEnabled);
            return gridFsId;
        } catch (Exception e) {
            obs.error(e);
            throw new RuntimeException("GridFS storage failed", e);
        } finally {
            obs.stop();
        }
    }

    /**
     * Compute SHA-256 hash for document integrity verification.
     */
    public String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return "hash-error";
        }
    }

    private byte[] encrypt(byte[] data) {
        // TODO: AES-256-GCM with key from AWS Secrets Manager
        log.warn("Using placeholder encryption — NOT for production");
        return Base64.getEncoder().encode(data);
    }
}