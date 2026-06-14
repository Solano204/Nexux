package com.nexus.payment.lambda.model.enums;


public enum PaymentStatus {
    PROCESSED,          // published to Kafka + SNS
    DUPLICATE,          // idempotency check — already processed
    VALIDATION_FAILED,  // failed input validation
    BRIDGE_FAILED,      // Kafka bridge unavailable
    PERMANENTLY_FAILED  // DLQ bound after all retries
}