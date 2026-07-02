package com.nexus.account.infrastructure.ai;

import com.nexus.account.infrastructure.mongodb.AccountAnalyticsDocument;
import com.nexus.account.infrastructure.mongodb.AccountAnalyticsRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Account Advisor Service — AI-powered financial advice.
 *
 * Implements Section 10 (RAG) + Section 7 (Memory)
 * + Section 3 (Streaming SSE response).
 *
 * Compatible with Spring AI 1.0.0-M6 API.
 *
 * The advisor has access to:
 * 1. User's actual transaction history (via pgvector QuestionAnswerAdvisor)
 * 2. Current account analytics (via MongoDB pre-aggregation)
 * 3. Short-term conversation context (via MessageChatMemoryAdvisor)
 */
@Slf4j
@Service
public class AccountAdvisorService {

    private final ChatClient accountAdvisorClient;
    private final AccountAnalyticsRepository analyticsRepository;
    private final ObservationRegistry observationRegistry;

    public AccountAdvisorService(
            @Qualifier("accountAdvisorClient") ChatClient accountAdvisorClient,
            AccountAnalyticsRepository analyticsRepository,
            ObservationRegistry observationRegistry) {
        this.accountAdvisorClient = accountAdvisorClient;
        this.analyticsRepository = analyticsRepository;
        this.observationRegistry = observationRegistry;
    }

    /**
     * Streaming AI advisor response — SSE streaming.
     *
     * Enriches the user's question with:
     * - Current account analytics summary (from MongoDB)
     * - pgvector user's transaction history RAG context (via advisor)
     * - Conversation context (via memory advisor)
     */
    public Flux<String> getAdvisorResponseStream(
            UUID accountId, UUID userId,
            String userMessage, String sessionId) {

        Observation obs = Observation.createNotStarted(
                "account.ai.advisor", observationRegistry).start();

        // Fetch current analytics for context enrichment
        String analyticsContext = analyticsRepository
                .findByAccountId(accountId.toString())
                .map(this::buildAnalyticsContext)
                .orElse("");

        String enrichedMessage = userMessage;
        if (!analyticsContext.isBlank()) {
            enrichedMessage = enrichedMessage +
                    "\n\n[Current Account Context]\n" + analyticsContext;
        }

        final String finalMessage = enrichedMessage;

        return accountAdvisorClient.prompt()
                .user(finalMessage)
                .advisors(a -> a
                        .param("chat_memory_conversation_id", sessionId))
                .stream()
                .content()
                .doOnComplete(() -> {
                    obs.event(Observation.Event.of("advisor.stream.complete"));
                    obs.stop();
                })
                .doOnError(e -> {
                    obs.error(e);
                    obs.stop();
                    log.error("AI advisor stream error for accountId={}: {}",
                            accountId, e.getMessage());
                });
    }

    /**
     * Non-streaming advice for proactive weekly insights.
     * Stores result in MongoDB savingsOpportunities.
     */
    public FinancialAdviceResponse getProactiveAdvice(UUID accountId) {

        Observation obs = Observation.createNotStarted(
                "account.ai.proactive-advice", observationRegistry).start();

        try {
            String analyticsContext = analyticsRepository
                    .findByAccountId(accountId.toString())
                    .map(this::buildAnalyticsContext)
                    .orElse("No analytics data available");

            return accountAdvisorClient.prompt()
                    .system("""
                        Generate a proactive weekly financial insight.
                        Be specific with numbers. Identify top 3 savings opportunities.
                        Return structured JSON matching FinancialAdviceResponse schema.
                        """)
                    .user("Analyze the past month for account " + accountId +
                            "\n\n" + analyticsContext)
                    .call()
                    .entity(FinancialAdviceResponse.class);

        } finally {
            obs.stop();
        }
    }

    private String buildAnalyticsContext(AccountAnalyticsDocument doc) {
        if (doc.getCurrentPeriod() == null) return "";
        var period = doc.getCurrentPeriod();

        StringBuilder sb = new StringBuilder();
        sb.append("Monthly spending: MXN ")
                .append(period.getTotalSpent()).append("\n");
        sb.append("Monthly income: MXN ")
                .append(period.getTotalReceived()).append("\n");
        sb.append("Transaction count: ")
                .append(period.getTransactionCount()).append("\n");

        if (period.getSpendingByCategory() != null) {
            sb.append("Spending by category:\n");
            period.getSpendingByCategory().forEach((cat, amt) ->
                    sb.append("  ").append(cat).append(": MXN ")
                            .append(amt).append("\n"));
        }
        return sb.toString();
    }

    /**
     * Structured output record for proactive advice.
     */
    public record FinancialAdviceResponse(
            String summary,
            List<SavingOpportunity> opportunities,
            BigDecimal estimatedMonthlySavings,
            List<String> actionItems
    ) {}

    public record SavingOpportunity(
            String category,
            BigDecimal currentMonthlySpend,
            BigDecimal targetMonthlySpend,
            BigDecimal potentialSaving,
            String recommendation
    ) {}
}