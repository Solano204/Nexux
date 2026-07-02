package com.nexus.reporting.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * S3 report uploader with KMS encryption and object tagging.
 * All uploads use server-side encryption with the reports KMS key.
 */
public class S3ReportUploader {

    private static final Logger log =
        LoggerFactory.getLogger(S3ReportUploader.class);

    private final S3Client s3;
    private final String bucket;
    private final String kmsKeyArn;

    public S3ReportUploader(S3Client s3, String bucket, String kmsKeyArn) {
        this.s3 = s3;
        this.bucket = bucket;
        this.kmsKeyArn = kmsKeyArn;
    }

    public void upload(String key, byte[] content, String contentType) {
        upload(key, content, contentType, Map.of());
    }

    public void upload(String key, byte[] content, String contentType,
                       Map<String, String> tags) {
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength((long) content.length)
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(kmsKeyArn);

        if (!tags.isEmpty()) {
            String tagging = tags.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
            builder.tagging(tagging);
        }

        s3.putObject(builder.build(), RequestBody.fromBytes(content));
        log.info("Uploaded: s3://{}/{} ({} bytes, {})",
            bucket, key, content.length, contentType);
    }

    /**
     * Build a standardized S3 key for a daily report file.
     * Pattern: daily/{type}/{year}/{month}/nexus-{type}-{date}.{ext}
     */
    public static String buildKey(String reportType, LocalDate date, String ext) {
        return String.format("daily/%s/%d/%02d/nexus-%s-%s.%s",
            reportType, date.getYear(), date.getMonthValue(),
            reportType, date, ext);
    }
}
