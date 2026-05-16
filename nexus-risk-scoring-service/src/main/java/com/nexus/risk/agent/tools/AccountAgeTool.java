package com.nexus.risk.agent.tools;

import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component @RequiredArgsConstructor
public class AccountAgeTool {
    private final ObservationRegistry observationRegistry;

    @Tool(name = "account_age_tool",
            description = "Gets account age, types, and lifecycle events. " +
                    "Returns account age in months, types held, freeze/close history. " +
                    "Multiple account types = positive credit signal.")
    public String getAccountAge(
            @ToolParam(description = "User UUID") String userId) {
        var result = java.util.Map.of(
                "userId", userId,
                "accountAgeMonths", 14,
                "accountCount", 2,
                "accountTypes", java.util.List.of("CHECKING", "SAVINGS"),
                "hasMultipleAccountTypes", true,
                "hasHistoricalFreeze", false,
                "totalSavingsBalance", "12500.00");
        return toJson(result);
    }
    private String toJson(Object o) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(o); }
        catch (Exception e) { return o.toString(); }
    }
}

@Component @RequiredArgsConstructor
class KycStatusTool {
    private final ObservationRegistry observationRegistry;

    @Tool(name = "kyc_status_tool",
            description = "Gets KYC verification status. Returns tier, " +
                    "document type, days since verification, rejection history. " +
                    "MANDATORY — KYC quality is a key compliance signal.")
    public String getKycStatus(
            @ToolParam(description = "User UUID") String userId) {
        var result = java.util.Map.of(
                "userId", userId,
                "isKycApproved", true,
                "kycTier", "STANDARD",
                "documentType", "NATIONAL_ID",
                "daysSinceVerification", 45,
                "reVerificationDue", false,
                "attemptsBeforeApproval", 1,
                "hasPreviousRejections", false,
                "documentFraudFlag", false,
                "edsApplied", false);
        try { return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(result); }
        catch (Exception e) { return result.toString(); }
    }
}

@Component @RequiredArgsConstructor
class ExternalCreditTool {
    private final ObservationRegistry observationRegistry;

    @Tool(name = "external_credit_tool",
            description = "Queries mock Buró de Crédito data. Only call " +
                    "if user has < 6 months internal history. Returns external " +
                    "credit score, payment history, debt levels. Slow: 500ms.")
    public String getExternalCredit(
            @ToolParam(description = "User UUID") String userId) {
        // Deterministic mock based on userId hash
        int seed = userId.hashCode();
        var rng = new Random(seed);
        int score = 400 + rng.nextInt(450);
        var result = java.util.Map.of(
                "userId", userId,
                "externalCreditScore", score,
                "paymentHistoryGrade",
                score >= 750 ? "A" : score >= 670 ? "B"
                        : score >= 580 ? "C" : "D",
                "hasDerogatory", score < 550 && rng.nextBoolean(),
                "numberOfInquiries", rng.nextInt(5),
                "creditUtilization", rng.nextDouble() * 0.5,
                "dataSource", "MOCK_BURO_DE_CREDITO");
        try { return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(result); }
        catch (Exception e) { return result.toString(); }
    }
}

@Component @RequiredArgsConstructor
class SpendingPatternTool {
    private final ObservationRegistry observationRegistry;

    @Tool(name = "spending_pattern_tool",
            description = "Analyzes spending consistency and recurring payments. " +
                    "Returns consistency score, detected recurring bills, " +
                    "pattern breaks. Regular bills = positive financial responsibility signal.")
    public String analyzeSpendingPattern(
            @ToolParam(description = "User UUID") String userId) {
        var result = java.util.Map.of(
                "userId", userId,
                "spendingConsistencyScore", 0.82,
                "monthlySpendingCV", 0.15,
                "isPatternStable", true,
                "recurringPaymentsCount", 4,
                "hasRegularBillPayments", true,
                "patternBreaks", java.util.List.of());
        try { return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(result); }
        catch (Exception e) { return result.toString(); }
    }
}

@Component @RequiredArgsConstructor
class IncomeAnalysisTool {
    private final ObservationRegistry observationRegistry;

    @Tool(name = "income_analysis_tool",
            description = "Analyzes income sources and stability. Returns " +
                    "income type (SALARY/BUSINESS/TRANSFERS), monthly estimate, " +
                    "stability score, income gaps. Regular salary = strongest positive signal.")
    public String analyzeIncome(
            @ToolParam(description = "User UUID") String userId) {
        var result = java.util.Map.of(
                "userId", userId,
                "primaryIncomeType", "SALARY",
                "estimatedMonthlyIncome", "35000.00",
                "incomeStabilityScore", 0.92,
                "incomeConsistencyCV", 0.08,
                "monthsWithLowOrNoIncome", 0,
                "hasRegularSalaryPattern", true,
                "estimatedAnnualIncome", "420000.00");
        try { return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(result); }
        catch (Exception e) { return result.toString(); }
    }
}

@Component @RequiredArgsConstructor
class CounterpartyAnalysisTool {
    private final ObservationRegistry observationRegistry;

    @Tool(name = "counterparty_analysis_tool",
            description = "Analyzes transaction counterparties. Returns total " +
                    "unique counterparties, recurring vs one-time, flagged accounts. " +
                    "High diversity + rapid movement = AML signal.")
    public String analyzeCounterparties(
            @ToolParam(description = "User UUID") String userId) {
        var result = java.util.Map.of(
                "userId", userId,
                "totalUniqueCounterparties", 23,
                "recurringCounterparties", 8,
                "oneTimeCounterparties", 15,
                "flaggedCounterparties", java.util.List.of(),
                "hasFlaggedCounterparties", false,
                "counterpartyDiversityScore", 0.35,
                "knownSafeCounterparties",
                java.util.List.of("acc-001", "acc-002", "acc-003"));
        try { return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(result); }
        catch (Exception e) { return result.toString(); }
    }
}

@Component @RequiredArgsConstructor
class GeographicRiskTool {
    private final ObservationRegistry observationRegistry;

    @Tool(name = "geographic_risk_tool",
            description = "Analyzes geographic patterns. Returns primary country, " +
                    "high-risk country exposure, impossible travel events. " +
                    "Call when: fraud flags exist OR risk tier is HIGH/VERY_HIGH.")
    public String analyzeGeographicRisk(
            @ToolParam(description = "User UUID") String userId) {
        var result = java.util.Map.of(
                "userId", userId,
                "primaryCountry", "MX",
                "uniqueCountriesActive", 1,
                "highRiskCountriesEngaged", java.util.List.of(),
                "hasHighRiskCountryExposure", false,
                "impossibleTravelEvents", java.util.List.of(),
                "hasImpossibleTravel", false,
                "geographicConsistencyScore", 0.97);
        try { return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(result); }
        catch (Exception e) { return result.toString(); }
    }
}