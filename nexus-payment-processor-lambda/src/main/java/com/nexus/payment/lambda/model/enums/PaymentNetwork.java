package com.nexus.payment.lambda.model.enums;

public enum PaymentNetwork {
    VISA, MASTERCARD, AMEX, SPEI, OTHER;

    public static PaymentNetwork from(String value) {
        if (value == null) return OTHER;
        return switch (value.toUpperCase().trim()) {
            case "VISA", "VI"     -> VISA;
            case "MASTERCARD","MC" -> MASTERCARD;
            case "AMEX", "AX"     -> AMEX;
            case "SPEI"           -> SPEI;
            default               -> OTHER;
        };
    }
}