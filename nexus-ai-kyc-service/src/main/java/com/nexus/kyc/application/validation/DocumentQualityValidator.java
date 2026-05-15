package com.nexus.kyc.application.validation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Document Quality Validator — Image pre-checks before AI vision.
 *
 * Validates the uploaded file before sending to OpenAI:
 * - File size: must be ≤ 10MB (OpenAI vision limit)
 * - File type: only JPEG, PNG, WebP supported
 * - File not empty
 * - Basic image header validation (magic bytes)
 *
 * These are cheap byte-level checks that prevent wasting
 * API credits on obviously invalid uploads.
 */
@Component
public class DocumentQualityValidator {

    private static final long MAX_FILE_SIZE_BYTES =
            10 * 1024 * 1024; // 10MB

    private static final java.util.Set<String> ALLOWED_MIME_TYPES =
            java.util.Set.of(
                    "image/jpeg", "image/jpg",
                    "image/png", "image/webp"
            );

    public QualityCheckResult validate(byte[] imageBytes,
                                       String mimeType) {
        List<String> issues = new ArrayList<>();

        if (imageBytes == null || imageBytes.length == 0) {
            issues.add("FILE_EMPTY");
            return new QualityCheckResult(false, issues);
        }

        if (imageBytes.length > MAX_FILE_SIZE_BYTES) {
            issues.add("FILE_TOO_LARGE: " +
                    (imageBytes.length / 1024 / 1024) + "MB");
        }

        if (mimeType == null ||
                !ALLOWED_MIME_TYPES.contains(
                        mimeType.toLowerCase())) {
            issues.add("UNSUPPORTED_FORMAT: " + mimeType);
        }

        // Magic byte validation
        if (!hasValidImageHeader(imageBytes, mimeType)) {
            issues.add("INVALID_IMAGE_HEADER");
        }

        return new QualityCheckResult(issues.isEmpty(), issues);
    }

    private boolean hasValidImageHeader(byte[] bytes,
                                        String mimeType) {
        if (bytes.length < 4) return false;

        if (mimeType != null &&
                (mimeType.contains("jpeg") ||
                        mimeType.contains("jpg"))) {
            // JPEG magic: FF D8 FF
            return (bytes[0] & 0xFF) == 0xFF &&
                    (bytes[1] & 0xFF) == 0xD8 &&
                    (bytes[2] & 0xFF) == 0xFF;
        }

        if (mimeType != null && mimeType.contains("png")) {
            // PNG magic: 89 50 4E 47
            return (bytes[0] & 0xFF) == 0x89 &&
                    (bytes[1] & 0xFF) == 0x50 &&
                    (bytes[2] & 0xFF) == 0x4E &&
                    (bytes[3] & 0xFF) == 0x47;
        }

        return true; // Allow other types through for AI to assess
    }

    public record QualityCheckResult(
            boolean passed, List<String> issues
    ) {}
}