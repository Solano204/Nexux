package com.nexus.assistant.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class SavingsRecommendationsTool {
    @Tool(name = "get_savings_recommendations",
            description = "Gets pre-computed AI savings recommendations based on spending history.")
    public String getSavingsRecommendations(
            @ToolParam(description = "Account UUID") String accountId) {
        return "{\"status\": \"RECOMMENDATIONS_AVAILABLE\"}";
    }
}