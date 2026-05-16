package com.nexus.payment.lambda.bridge;


import com.nexus.payment.lambda.model.enums.FailureReason;

public record KafkaBridgeResult(
        boolean success,
        String topic,
        int partition,
        long offset,
        long durationMs,
        FailureReason failureReason,
        String failureDetail
) {
    public static KafkaBridgeResult success(String topic,
                                            int partition,
                                            long offset,
                                            long durationMs) {
        return new KafkaBridgeResult(true, topic, partition,
                offset, durationMs, FailureReason.NONE, null);
    }

    public static KafkaBridgeResult failure(FailureReason reason,
                                            String detail) {
        return new KafkaBridgeResult(false, null, -1, -1, 0,
                reason, detail);
    }
}}
