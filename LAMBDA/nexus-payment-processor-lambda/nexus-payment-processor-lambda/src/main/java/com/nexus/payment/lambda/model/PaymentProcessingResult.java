package com.nexus.payment.lambda.model;

import com.nexus.payment.lambda.model.enums.FailureReason;
import com.nexus.payment.lambda.model.enums.PaymentStatus;

/**
 * PaymentProcessingResult — outcome of processing a single SQS message.
 * Includes Kafka bridge metadata (topic/partition/offset) on success,
 * or failure reason + detail on failure.
 */
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
