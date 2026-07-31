package com.nexus.kyc.unit;

import com.nexus.kyc.application.validation.DocumentQualityValidator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentQualityValidatorTest {

    private final DocumentQualityValidator validator = new DocumentQualityValidator();

    private byte[] jpegBytes(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private byte[] pngBytes(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0x89;
        bytes[1] = 0x50;
        bytes[2] = 0x4E;
        bytes[3] = 0x47;
        return bytes;
    }

    @Test
    void rejectsNullBytes() {
        var result = validator.validate(null, "image/jpeg");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).contains("FILE_EMPTY");
    }

    @Test
    void rejectsEmptyBytes() {
        var result = validator.validate(new byte[0], "image/jpeg");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).contains("FILE_EMPTY");
    }

    @Test
    void acceptsValidJpegUnderSizeLimit() {
        var result = validator.validate(jpegBytes(1024), "image/jpeg");

        assertThat(result.passed()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void acceptsValidPng() {
        var result = validator.validate(pngBytes(1024), "image/png");

        assertThat(result.passed()).isTrue();
    }

    @Test
    void rejectsFileOverTenMegabytes() {
        var result = validator.validate(jpegBytes(11 * 1024 * 1024), "image/jpeg");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.startsWith("FILE_TOO_LARGE"));
    }

    @Test
    void rejectsUnsupportedMimeType() {
        var result = validator.validate(jpegBytes(1024), "application/pdf");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.startsWith("UNSUPPORTED_FORMAT"));
    }

    @Test
    void rejectsNullMimeType() {
        var result = validator.validate(jpegBytes(1024), null);

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.startsWith("UNSUPPORTED_FORMAT"));
    }

    @Test
    void rejectsJpegWithWrongMagicBytes() {
        byte[] bytes = new byte[]{0x00, 0x00, 0x00, 0x00};
        var result = validator.validate(bytes, "image/jpeg");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).contains("INVALID_IMAGE_HEADER");
    }

    @Test
    void rejectsPngWithWrongMagicBytes() {
        byte[] bytes = new byte[]{0x00, 0x00, 0x00, 0x00};
        var result = validator.validate(bytes, "image/png");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).contains("INVALID_IMAGE_HEADER");
    }

    @Test
    void rejectsBytesShorterThanFourAsInvalidHeader() {
        var result = validator.validate(new byte[]{0x01, 0x02}, "image/jpeg");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).contains("INVALID_IMAGE_HEADER");
    }

    @Test
    void allowsWebpThroughHeaderCheckRegardlessOfBytes() {
        byte[] bytes = jpegBytes(1024); // arbitrary bytes, not a real WebP header
        var result = validator.validate(bytes, "image/webp");

        assertThat(result.passed()).isTrue();
    }

    @Test
    void acceptsMimeTypeCaseInsensitively() {
        var result = validator.validate(jpegBytes(1024), "IMAGE/JPEG");

        assertThat(result.passed()).isTrue();
    }

    @Test
    void accumulatesMultipleIssuesSimultaneously() {
        byte[] tooLargeAndInvalid = new byte[11 * 1024 * 1024];
        var result = validator.validate(tooLargeAndInvalid, "application/pdf");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).hasSizeGreaterThanOrEqualTo(2);
    }
}
