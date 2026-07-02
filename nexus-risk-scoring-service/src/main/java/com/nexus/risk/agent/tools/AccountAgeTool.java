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