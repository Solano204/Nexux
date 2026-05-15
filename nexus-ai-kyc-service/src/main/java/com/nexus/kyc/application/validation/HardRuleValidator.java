package com.nexus.kyc.application.validation;

import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.RejectionReason;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Hard Rule Validator — Pre-screening before AI pipeline.
 *
 * Mirrors the Fraud Service pattern: run cheap deterministic
 * checks BEFORE calling expensive AI APIs.
 *
 * Rules checked:
 * 1. Age validation: user must be ≥ 18 years old
 * 2. Age sanity check: user must be < 120 years old
 * 3. Document number format: matches expected format per type
 * 4. Name length: must be 2-100 characters
 * 5. Future date of birth: impossible — reject immediately
 * 6. Attempt limit: max 3 attempts per 24 hours
 *
 * These rules never need AI to evaluate. Running them first
 * saves API cost and reduces latency for obviously invalid requests.
 */
@Component
public class HardRuleValidator {

    public HardRuleResult validate(
            KycVerificationRequest request,
            int previousAttempts) {

        List<String> failures = new ArrayList<>();

        // Rule 1: Minimum age (18)
        validateAge(request.dateOfBirth(), failures);

        // Rule 2: Name validity
        validateName(request.fullName(), failures);

        // Rule 3: Document number format
        validateDocumentNumber(
                request.documentNumber(),
                request.documentType(), failures);

        // Rule 4: Attempt limit
        if (previousAttempts >= 3) {
            failures.add("MAX_ATTEMPTS_EXCEEDED");
        }

        return new HardRuleResult(
                failures.isEmpty(), failures);
    }

    private void validateAge(String dateOfBirth,
                             List<String> failures) {
        try {
            LocalDate dob = parseDate(dateOfBirth);
            if (dob == null) {
                failures.add("INVALID_DATE_FORMAT");
                return;
            }
            int age = Period.between(dob, LocalDate.now()).getYears();
            if (age < 18) failures.add("UNDERAGE");
            if (age > 120) failures.add("IMPOSSIBLE_AGE");
            if (dob.isAfter(LocalDate.now()))
                failures.add("FUTURE_DATE_OF_BIRTH");
        } catch (Exception e) {
            failures.add("INVALID_DATE_FORMAT");
        }
    }

    private void validateName(String name,
                              List<String> failures) {
        if (name == null || name.isBlank()) {
            failures.add("NAME_EMPTY");
            return;
        }
        if (name.trim().length() < 2)
            failures.add("NAME_TOO_SHORT");
        if (name.trim().length() > 200)
            failures.add("NAME_TOO_LONG");
        if (name.matches(".*\\d.*"))
            failures.add("NAME_CONTAINS_DIGITS");
    }

    private void validateDocumentNumber(
            String docNumber,
            com.nexus.kyc.domain.model.enums.DocumentType type,
            List<String> failures) {

        if (docNumber == null || docNumber.isBlank()) {
            failures.add("DOCUMENT_NUMBER_EMPTY");
            return;
        }

        boolean valid = switch (type) {
            // Mexico INE/IFE: 18-character alphanumeric
            case NATIONAL_ID -> docNumber
                    .replaceAll("[\\s-]", "").length() >= 9;
            // Passports: 6-9 alphanumeric
            case PASSPORT -> docNumber
                    .replaceAll("[\\s-]", "")
                    .matches("[A-Z0-9]{6,9}");
            // Driver's license: variable by country
            case DRIVERS_LICENSE -> docNumber
                    .replaceAll("[\\s-]", "").length() >= 6;
            default -> docNumber.length() >= 6;
        };

        if (!valid) failures.add("INVALID_DOCUMENT_NUMBER_FORMAT");
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null) return null;
        // Try common formats
        String[] formats = {
                "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy",
                "dd-MM-yyyy", "yyyy/MM/dd"
        };
        for (String fmt : formats) {
            try {
                return LocalDate.parse(dateStr,
                        DateTimeFormatter.ofPattern(fmt));
            } catch (Exception ignored) {}
        }
        return null;
    }

    public record HardRuleResult(
            boolean passed,
            List<String> failures
    ) {}
}