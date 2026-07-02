package com.nexus.fraud.lambda.model;

public record SarConsiderationResult(
        boolean required,
        String sarId,
        String reason,
        String patternType
) {
    public static SarConsiderationResult notRequired() {
        return new SarConsiderationResult(
                false, null, null, null);
    }
}
