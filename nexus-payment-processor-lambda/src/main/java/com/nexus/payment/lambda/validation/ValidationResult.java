package com.nexus.payment.lambda.validation;


import com.nexus.payment.lambda.model.enums.FailureReason;

public record ValidationResult(
        boolean isValid,
        FailureReason failureReason,
        String failureDetail
) {
    public static ValidationResult valid() {
        return new ValidationResult(true, FailureReason.NONE, null);
    }

    public static ValidationResult invalid(FailureReason reason,
                                           String detail) {
        return new ValidationResult(false, reason, detail);
    }
}