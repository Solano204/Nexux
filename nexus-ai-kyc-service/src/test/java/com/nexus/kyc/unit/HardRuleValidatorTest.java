//package com.nexus.kyc.unit;
//
//import com.nexus.kyc.application.validation.HardRuleValidator;
//import com.nexus.kyc.domain.model.KycVerificationRequest;
//import com.nexus.kyc.domain.model.enums.DocumentType;
//import org.junit.jupiter.api.*;
//import org.junit.jupiter.params.ParameterizedTest;
//import org.junit.jupiter.params.provider.ValueSource;
//
//import static org.assertj.core.api.Assertions.*;
//
//@Tag("unit")
//class HardRuleValidatorTest {
//
//    HardRuleValidator validator = new HardRuleValidator();
//
//    @Test
//    @DisplayName("Valid adult passes all hard rules")
//    void validate_validAdult_passes() {
//        var request = new KycVerificationRequest(
//                "user-1", "Juan García", "1990-03-15",
//                "A12345678", DocumentType.PASSPORT, "MX", "es");
//
//        var result = validator.validate(request, 0);
//
//        assertThat(result.passed()).isTrue();
//        assertThat(result.failures()).isEmpty();
//    }
//
//    @Test
//    @DisplayName("Underage user is rejected by hard rule")
//    void validate_underage_fails() {
//        var request = new KycVerificationRequest(
//                "user-2", "María López",
//                java.time.LocalDate.now().minusYears(16).toString(),
//                "B98765432", DocumentType.NATIONAL_ID, "MX", "es");
//
//        var result = validator.validate(request, 0);
//
//        assertThat(result.passed()).isFalse();
//        assertThat(result.failures()).contains("UNDERAGE");
//    }
//
//    @Test
//    @DisplayName("Future date of birth is rejected")
//    void validate_futureDateOfBirth_fails() {
//        var request = new KycVerificationRequest(
//                "user-3", "Carlos Ruiz",
//                java.time.LocalDate.now().plusDays(1).toString(),
//                "C11111111", DocumentType.PASSPORT, "MX", "es");
//
//        var result = validator.validate(request, 0);
//
//        assertThat(result.passed()).isFalse();
//        assertThat(result.failures()).contains("FUTURE_DATE_OF_BIRTH");
//    }
//
//    @Test
//    @DisplayName("Max attempts exceeded is rejected")
//    void validate_maxAttempts_fails() {
//        var request = new KycVerificationRequest(
//                "user-4", "Ana Torres", "1985-07-20",
//                "D55555555", DocumentType.NATIONAL_ID, "MX", "es");
//
//        var result = validator.validate(request, 3);
//
//        assertThat(result.passed()).isFalse();
//        assertThat(result.failures()).contains("MAX_ATTEMPTS_EXCEEDED");
//    }
//
//    @ParameterizedTest
//    @DisplayName("Invalid document numbers are rejected")
//    @ValueSource(strings = {"A", "12", ""})
//    void validate_shortDocumentNumber_fails(String docNum) {
//        var request = new KycVerificationRequest(
//                "user-5", "Pedro Sánchez", "1988-12-01",
//                docNum, DocumentType.PASSPORT, "MX", "es");
//
//        var result = validator.validate(request, 0);
//
//        assertThat(result.passed()).isFalse();
//    }
//
//    @Test
//    @DisplayName("Name with digits is rejected")
//    void validate_nameWithDigits_fails() {
//        var request = new KycVerificationRequest(
//                "user-6", "Juan123 García", "1992-05-10",
//                "E99999999", DocumentType.NATIONAL_ID, "MX", "es");
//
//        var result = validator.validate(request, 0);
//
//        assertThat(result.passed()).isFalse();
//        assertThat(result.failures()).contains("NAME_CONTAINS_DIGITS");
//    }
//}
//
//@Tag("unit")
//class DocumentQualityValidatorTest {
//
//    DocumentQualityValidator validator =
//            new DocumentQualityValidator();
//
//    @Test
//    @DisplayName("Valid JPEG passes quality check")
//    void validate_validJpeg_passes() {
//        // JPEG magic bytes: FF D8 FF E0 ...
//        byte[] jpeg = new byte[100];
//        jpeg[0] = (byte) 0xFF;
//        jpeg[1] = (byte) 0xD8;
//        jpeg[2] = (byte) 0xFF;
//        jpeg[3] = (byte) 0xE0;
//
//        var result = validator.validate(jpeg, "image/jpeg");
//
//        assertThat(result.passed()).isTrue();
//    }
//
//    @Test
//    @DisplayName("Empty file fails quality check")
//    void validate_emptyFile_fails() {
//        var result = validator.validate(new byte[0], "image/jpeg");
//        assertThat(result.passed()).isFalse();
//        assertThat(result.issues()).contains("FILE_EMPTY");
//    }
//
//    @Test
//    @DisplayName("Unsupported file type fails")
//    void validate_unsupportedType_fails() {
//        var result = validator.validate(
//                new byte[100], "application/pdf");
//        assertThat(result.passed()).isFalse();
//        assertThat(result.issues())
//                .anyMatch(i -> i.contains("UNSUPPORTED_FORMAT"));
//    }
//
//    @Test
//    @DisplayName("File over 10MB fails")
//    void validate_tooLarge_fails() {
//        byte[] large = new byte[11 * 1024 * 1024]; // 11MB
//        large[0] = (byte) 0xFF;
//        large[1] = (byte) 0xD8;
//        large[2] = (byte) 0xFF;
//
//        var result = validator.validate(large, "image/jpeg");
//
//        assertThat(result.passed()).isFalse();
//        assertThat(result.issues())
//                .anyMatch(i -> i.contains("FILE_TOO_LARGE"));
//    }
//}