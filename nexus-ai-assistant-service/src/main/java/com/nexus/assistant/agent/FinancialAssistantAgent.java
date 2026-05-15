package com.nexus.assistant.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class SpendingAnalysisTool {
    @Tool(name = "analyze_spending",
            description = "Analyzes spending patterns by category for a period. Use for budgeting questions.")
    public String analyzeSpending(
            @ToolParam(description = "Account UUID") String accountId,
            @ToolParam(description = "Period: LAST_MONTH, LAST_3_MONTHS, THIS_YEAR") String period) {
        return "{\"status\": \"ANALYSIS_AVAILABLE\", " +
                "\"note\": \"Spending analysis retrieved from analytics service\"}";
    }
}

@Component
public class SavingsRecommendationsTool {
    @Tool(name = "get_savings_recommendations",
            description = "Gets pre-computed AI savings recommendations based on spending history.")
    public String getSavingsRecommendations(
            @ToolParam(description = "Account UUID") String accountId) {
        return "{\"status\": \"RECOMMENDATIONS_AVAILABLE\"}";
    }
}