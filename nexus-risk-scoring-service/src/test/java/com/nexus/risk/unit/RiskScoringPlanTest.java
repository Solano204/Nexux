package com.nexus.risk.unit;

import com.nexus.risk.agent.model.UserContext;
import com.nexus.risk.domain.model.enums.CreditGrade;
import com.nexus.risk.domain.model.enums.RiskTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScoringPlanTest {

    @Test
    void newUserContextReflectsShallowScoringInputs() {
        var ctx = UserContext.builder()
                .userId("new-user")
                .accountAgeMonths(0)
                .hasTransactionHistory(false)
                .monthsOfHistoryAvailable(0)
                .kycStatus("VERIFIED")
                .recentFraudFlags(false)
                .significantBehavioralChange(false)
                .build();

        assertThat(ctx.accountAgeMonths()).isEqualTo(0);
        assertThat(ctx.hasTransactionHistory()).isFalse();
        assertThat(ctx.recentFraudFlags()).isFalse();
    }

    @Test
    void highRiskUserContextReflectsComprehensiveScoringInputs() {
        var ctx = UserContext.builder()
                .userId("high-risk-user")
                .accountAgeMonths(18)
                .hasTransactionHistory(true)
                .monthsOfHistoryAvailable(18)
                .previousRiskTier("HIGH")
                .recentFraudFlags(true)
                .significantBehavioralChange(true)
                .build();

        assertThat(ctx.recentFraudFlags()).isTrue();
        assertThat(ctx.previousRiskTier()).isEqualTo("HIGH");
        assertThat(ctx.significantBehavioralChange()).isTrue();
    }

    @Test
    void userContextWithCreatesModifiedCopy() {
        var original = UserContext.builder()
                .userId("user-1").accountAgeMonths(5)
                .accountTypes(List.of("CHECKING"))
                .build();

        var updated = original.withAccountAgeMonths(10);

        assertThat(original.accountAgeMonths()).isEqualTo(5);
        assertThat(updated.accountAgeMonths()).isEqualTo(10);
        assertThat(updated.userId()).isEqualTo("user-1");
    }

    @Test
    void riskTierMapsLowerBoundariesInclusively() {
        assertThat(RiskTier.fromScore(0)).isEqualTo(RiskTier.VERY_LOW);
        assertThat(RiskTier.fromScore(20)).isEqualTo(RiskTier.VERY_LOW);
        assertThat(RiskTier.fromScore(21)).isEqualTo(RiskTier.LOW);
        assertThat(RiskTier.fromScore(40)).isEqualTo(RiskTier.LOW);
        assertThat(RiskTier.fromScore(41)).isEqualTo(RiskTier.MEDIUM);
        assertThat(RiskTier.fromScore(60)).isEqualTo(RiskTier.MEDIUM);
        assertThat(RiskTier.fromScore(61)).isEqualTo(RiskTier.HIGH);
        assertThat(RiskTier.fromScore(80)).isEqualTo(RiskTier.HIGH);
        assertThat(RiskTier.fromScore(81)).isEqualTo(RiskTier.VERY_HIGH);
        assertThat(RiskTier.fromScore(100)).isEqualTo(RiskTier.VERY_HIGH);
    }

    @Test
    void riskTierScoreMappingMatchesDocumentedExamples() {
        assertThat(RiskTier.fromScore(10)).isEqualTo(RiskTier.VERY_LOW);
        assertThat(RiskTier.fromScore(30)).isEqualTo(RiskTier.LOW);
        assertThat(RiskTier.fromScore(50)).isEqualTo(RiskTier.MEDIUM);
        assertThat(RiskTier.fromScore(70)).isEqualTo(RiskTier.HIGH);
        assertThat(RiskTier.fromScore(90)).isEqualTo(RiskTier.VERY_HIGH);
    }

    @Test
    void creditGradeMapsBoundariesInclusively() {
        assertThat(CreditGrade.fromScore(750)).isEqualTo(CreditGrade.A);
        assertThat(CreditGrade.fromScore(749)).isEqualTo(CreditGrade.B);
        assertThat(CreditGrade.fromScore(670)).isEqualTo(CreditGrade.B);
        assertThat(CreditGrade.fromScore(669)).isEqualTo(CreditGrade.C);
        assertThat(CreditGrade.fromScore(580)).isEqualTo(CreditGrade.C);
        assertThat(CreditGrade.fromScore(579)).isEqualTo(CreditGrade.D);
        assertThat(CreditGrade.fromScore(500)).isEqualTo(CreditGrade.D);
        assertThat(CreditGrade.fromScore(499)).isEqualTo(CreditGrade.F);
    }

    @Test
    void creditGradeScoreMappingMatchesDocumentedExamples() {
        assertThat(CreditGrade.fromScore(780)).isEqualTo(CreditGrade.A);
        assertThat(CreditGrade.fromScore(700)).isEqualTo(CreditGrade.B);
        assertThat(CreditGrade.fromScore(600)).isEqualTo(CreditGrade.C);
        assertThat(CreditGrade.fromScore(520)).isEqualTo(CreditGrade.D);
        assertThat(CreditGrade.fromScore(400)).isEqualTo(CreditGrade.F);
    }
}
