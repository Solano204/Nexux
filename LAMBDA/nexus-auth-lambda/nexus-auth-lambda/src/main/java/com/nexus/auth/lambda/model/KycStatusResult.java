package com.nexus.auth.lambda.model;

public record KycStatusResult(
        boolean isVerified,
        String verifiedAt,
        String kycTier,
        int daysSinceVerification,
        boolean reVerificationDue
) {
    public static KycStatusResult fromSession(SessionRecord session) {
        return new KycStatusResult(
                session.kycVerified(),
                null, "STANDARD", 0, false);
    }
}