package com.nexus.payment.lambda.model;
public record PaymentProcessingResult(
        String networkTransactionId,
        String eventId,
        PaymentStatus status,
        String kafkaTopic,
        String kafkaPartition,
        String kafkaOffset,
        String snsMessageId,
        FailureReason failureReason,
        String failureDetail,
        long processingMs
) {}