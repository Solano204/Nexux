package com.nexus.kyc.unit;

import com.nexus.kyc.application.validation.HardRuleValidator;
import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.DocumentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HardRuleValidatorTest {

    private final HardRuleValidator validator = new HardRuleValidator();

    @Test
    @DisplayName("Valid adult passes all hard rules")
    void validate_validAdult_passes() {
        var request = new KycVerificationRequest(
                "user-1", "Juan García", "1990-03-15",
                "A12345678", DocumentType.PASSPORT, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.passed()).isTrue();
        assertThat(result.failures()).isEmpty();
    }

    @Test
    @DisplayName("Underage user is rejected by hard rule")
    void validate_underage_fails() {
        var request = new KycVerificationRequest(
                "user-2", "María López",
                java.time.LocalDate.now().minusYears(16).toString(),
                "B98765432", DocumentType.NATIONAL_ID, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).contains("UNDERAGE");
    }

    @Test
    @DisplayName("Future date of birth is rejected")
    void validate_futureDateOfBirth_fails() {
        var request = new KycVerificationRequest(
                "user-3", "Carlos Ruiz",
                java.time.LocalDate.now().plusDays(1).toString(),
                "C11111111", DocumentType.PASSPORT, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).contains("FUTURE_DATE_OF_BIRTH");
    }

    @Test
    @DisplayName("Max attempts exceeded is rejected")
    void validate_maxAttempts_fails() {
        var request = new KycVerificationRequest(
                "user-4", "Ana Torres", "1985-07-20",
                "D55555555", DocumentType.NATIONAL_ID, "MX", "es");

        var result = validator.validate(request, 3);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).contains("MAX_ATTEMPTS_EXCEEDED");
    }

    @ParameterizedTest
    @DisplayName("Invalid passport document numbers are rejected")
    @ValueSource(strings = {"A", "12", ""})
    void validate_shortDocumentNumber_fails(String docNum) {
        var request = new KycVerificationRequest(
                "user-5", "Pedro Sánchez", "1988-12-01",
                docNum, DocumentType.PASSPORT, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.passed()).isFalse();
    }

    @Test
    @DisplayName("Name with digits is rejected")
    void validate_nameWithDigits_fails() {
        var request = new KycVerificationRequest(
                "user-6", "Juan123 García", "1992-05-10",
                "E99999999", DocumentType.NATIONAL_ID, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).contains("NAME_CONTAINS_DIGITS");
    }

    @Test
    @DisplayName("Blank name is rejected")
    void validate_blankName_fails() {
        var request = new KycVerificationRequest(
                "user-7", "  ", "1990-01-01",
                "F11111111", DocumentType.NATIONAL_ID, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.failures()).contains("NAME_EMPTY");
    }

    @Test
    @DisplayName("Blank document number is rejected")
    void validate_blankDocumentNumber_fails() {
        var request = new KycVerificationRequest(
                "user-8", "Lucia Mendoza", "1990-01-01",
                "", DocumentType.NATIONAL_ID, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.failures()).contains("DOCUMENT_NUMBER_EMPTY");
    }

    @Test
    @DisplayName("Impossibly old age is rejected")
    void validate_impossibleAge_fails() {
        var request = new KycVerificationRequest(
                "user-9", "Ramon Vega",
                java.time.LocalDate.now().minusYears(130).toString(),
                "G22222222", DocumentType.NATIONAL_ID, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.failures()).contains("IMPOSSIBLE_AGE");
    }

    @Test
    @DisplayName("Unparseable date of birth is rejected")
    void validate_invalidDateFormat_fails() {
        var request = new KycVerificationRequest(
                "user-10", "Sofia Reyes", "not-a-date",
                "H33333333", DocumentType.NATIONAL_ID, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.failures()).contains("INVALID_DATE_FORMAT");
    }

    @Test
    @DisplayName("Two attempts (below the limit) still passes the attempt-count rule")
    void validate_belowAttemptLimit_passesAttemptRule() {
        var request = new KycVerificationRequest(
                "user-11", "Miguel Ortiz", "1990-01-01",
                "I44444444", DocumentType.NATIONAL_ID, "MX", "es");

        var result = validator.validate(request, 2);

        assertThat(result.failures()).doesNotContain("MAX_ATTEMPTS_EXCEEDED");
    }

    @Test
    @DisplayName("Valid driver's license document number passes")
    void validate_validDriversLicense_passes() {
        var request = new KycVerificationRequest(
                "user-12", "Elena Castro", "1995-06-15",
                "DL1234567", DocumentType.DRIVERS_LICENSE, "MX", "es");

        var result = validator.validate(request, 0);

        assertThat(result.passed()).isTrue();
    }
}
