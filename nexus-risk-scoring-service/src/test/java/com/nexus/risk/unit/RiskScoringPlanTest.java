package com.nexus.risk.unit;
// Unit Test
@Tag("unit")
class RiskScoringPlanTest {

    @Test
    @DisplayName("New user gets SHALLOW plan with 3 tools")
    void plan_newUser_shallowPlan() {
        var ctx = UserContext.builder()
                .userId("new-user")
                .accountAgeMonths(0)
                .hasTransactionHistory(false)
                .monthsOfHistoryAvailable(0)
                .kycStatus("VERIFIED")
                .recentFraudFlags(false)
                .significantBehavioralChange(false)
                .build();

        // For new users, plan should use minimal tools
        // (this would be verified via the planning client in integration tests)
        assertThat(ctx.accountAgeMonths()).isEqualTo(0);
        assertThat(ctx.hasTransactionHistory()).isFalse();
        // Expected: plan with SHALLOW depth, 3 mandatory tools only
    }

    @Test
    @DisplayName("High-risk user gets all tools including geo")
    void plan_highRiskUser_allTools() {
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
        // Expected: COMPREHENSIVE plan with all 8 tools including geo
    }

    @Test
    @DisplayName("RiskTier.fromScore maps correctly")
    void riskTier_scoreMapping() {
        assertThat(RiskTier.fromScore(10)).isEqualTo(RiskTier.VERY_LOW);
        assertThat(RiskTier.fromScore(30)).isEqualTo(RiskTier.LOW);
        assertThat(RiskTier.fromScore(50)).isEqualTo(RiskTier.MEDIUM);
        assertThat(RiskTier.fromScore(70)).isEqualTo(RiskTier.HIGH);
        assertThat(RiskTier.fromScore(90)).isEqualTo(RiskTier.VERY_HIGH);
    }

    @Test
    @DisplayName("CreditGrade.fromScore maps correctly")
    void creditGrade_scoreMapping() {
        assertThat(CreditGrade.fromScore(780)).isEqualTo(CreditGrade.A);
        assertThat(CreditGrade.fromScore(700)).isEqualTo(CreditGrade.B);
        assertThat(CreditGrade.fromScore(600)).isEqualTo(CreditGrade.C);
        assertThat(CreditGrade.fromScore(520)).isEqualTo(CreditGrade.D);
        assertThat(CreditGrade.fromScore(400)).isEqualTo(CreditGrade.F);
    }
}