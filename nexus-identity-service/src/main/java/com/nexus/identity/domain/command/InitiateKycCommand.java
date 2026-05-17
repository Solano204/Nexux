package com.nexus.identity.domain.command;

import java.util.UUID;

/**
 * InitiateKycCommand — starts the KYC verification flow.
 * S3 upload + SQS publish happen within Structured Concurrency scope.
 */
public record InitiateKycCommand(
        UUID userId,
        String documentType,
        String s3Path,
        String ipAddress,
        String traceId
) {}