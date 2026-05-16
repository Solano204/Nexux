package com.nexus.risk.domain.model.enums;
public enum CreditGrade {
    A, B, C, D, F;

    public static CreditGrade fromScore(int creditScore) {
        if (creditScore >= 750) return A;
        if (creditScore >= 670) return B;
        if (creditScore >= 580) return C;
        if (creditScore >= 500) return D;
        return F;
    }
}