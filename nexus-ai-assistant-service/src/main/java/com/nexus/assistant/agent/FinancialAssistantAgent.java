package com.nexus.assistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * Financial Assistant Agent — Plan-then-Act ReAct (Section 11).
 *
 * Two modes:
 * 1. SIMPLE: Standard ChatClient with tools (most queries)
 * 2. AGENT: Plan-then-Act loop for complex multi-step operations
 *
 * FIX APPLIED: ChatMemory.CONVERSATION_ID replaced with literal
 * "conversationId" string — the constant was removed in Spring AI 1.0.0-M6+.
 * The advisor reads this param key to identify the conversation.
 */
@Slf4j
@Component
public class FinancialAssistantAgent {

    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "conversationId";

    private final ChatClient primaryClient;
    private final ChatClient agentClient;
    private final ChatClient fallbackClient;
    private final ToolCallingManager toolCallingManager;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    private final Timer chatResponseTimer;
    private final Counter agentModeCounter;
    private final Counter fallbackCounter;
    private final Counter toolCallCounter;

    private static final int MAX_AGENT_STEPS = 8;

    public FinancialAssistantAgent(
            @Qualifier("aiAssistantPrimaryClient")
            ChatClient primaryClient,
            @Qualifier("aiAssistantAgentClient")
            ChatClient agentClient,
            @Qualifier("aiAssistantFallbackClient")
            ChatClient fallbackClient,
            ToolCallingManager toolCallingManager,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.primaryClient = primaryClient;
        this.agentClient = agentClient;
        this.fallbackClient = fallbackClient;
        this.toolCallingManager = toolCallingManager;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        this.chatResponseTimer =
                Timer.builder("ai.chat.response.duration")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry);

        this.agentModeCounter =
                Counter.builder("ai.chat.message.total")
                        .tag("mode", "AGENT")
                        .register(meterRegistry);

        this.fallbackCounter =
                Counter.builder("ai.provider.fallback.total")
                        .register(meterRegistry);

        this.toolCallCounter =
                Counter.builder("ai.tool.call.total")
                        .register(meterRegistry);
    }

    /**
     * Main streaming chat entry point (Section 3 SSE).
     */
    public Flux<String> chat(String message,
                             String conversationId,
                             String userId) {

        Timer.Sample sample = Timer.start();

        if (isComplexQuery(message)) {
            agentModeCounter.increment();
            return executeAgentMode(message, conversationId, userId)
                    .doFinally(s -> sample.stop(chatResponseTimer));
        }

        // Simple mode: standard streaming with tools
        return primaryClient.prompt()
                .user(message)
                .advisors(a -> a.param(
                        CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                .stream()
                .content()
                .onErrorResume(e -> {
                    fallbackCounter.increment();
                    log.warn("Primary client failed, using Ollama: {}",
                            e.getMessage());
                    return fallbackClient.prompt()
                            .user(message)
                            .advisors(a -> a.param(
                                    CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                            .stream()
                            .content();
                })
                .doFinally(s -> sample.stop(chatResponseTimer));
    }

    /**
     * Agent mode — Plan-then-Act loop (Section 11).
     */
    private Flux<String> executeAgentMode(String message,
                                          String conversationId,
                                          String userId) {
        return Flux.create(sink -> {
            Observation obs = Observation.createNotStarted(
                    "ai.agent.plan", observationRegistry).start();

            try (Observation.Scope scope = obs.openScope()) {

                sink.next("[Planning your request...]\n");

                List<Message> messages = new ArrayList<>();
                messages.add(new UserMessage(message));

                var toolOptions = ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build();

                ChatResponse chatResponse = agentClient.prompt()
                        .messages(messages)
                        .advisors(a -> a.param(
                                CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                        .call()
                        .chatResponse();

                obs.stop();

                // ReAct tool execution loop
                int steps = 0;
                var prompt = new org.springframework.ai.chat.prompt
                        .Prompt(messages, toolOptions);

                while (chatResponse.hasToolCalls() &&
                        steps < MAX_AGENT_STEPS) {

                    Observation stepObs = Observation.createNotStarted(
                            "ai.agent.step." + steps,
                            observationRegistry).start();

                    try (Observation.Scope stepScope =
                                 stepObs.openScope()) {

                        toolCallCounter.increment();

                        ToolExecutionResult result =
                                toolCallingManager.executeToolCalls(
                                        prompt, chatResponse);

                        prompt = new org.springframework.ai.chat.prompt
                                .Prompt(
                                result.conversationHistory(),
                                toolOptions);

                        chatResponse = agentClient.prompt()
                                .messages(result.conversationHistory())
                                .advisors(a -> a.param(
                                        CHAT_MEMORY_CONVERSATION_ID_KEY,
                                        conversationId))
                                .call()
                                .chatResponse();

                        stepObs.event(Observation.Event.of(
                                "agent.step.complete"));
                        steps++;

                    } finally {
                        stepObs.stop();
                    }
                }

                // Stream final synthesis
                agentClient.prompt()
                        .messages(prompt.getInstructions())
                        .advisors(a -> a.param(
                                CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                        .stream()
                        .content()
                        .subscribe(
                                sink::next,
                                error -> {
                                    log.error("Agent stream error: {}",
                                            error.getMessage());
                                    sink.error(error);
                                },
                                sink::complete
                        );

            } catch (Exception e) {
                log.error("Agent execution failed: {}",
                        e.getMessage(), e);
                sink.next("I encountered an issue processing your " +
                        "request. Please try again or contact support.");
                sink.complete();
            }
        });
    }

    /**
     * Detects complex queries requiring agent mode.
     */
    private boolean isComplexQuery(String message) {
        String lower = message.toLowerCase();
        return (lower.contains("transfer") &&
                (lower.contains("only if") ||
                        lower.contains("unless") ||
                        lower.contains("after checking")))
                || lower.contains("compare")
                || (lower.contains("and also") && lower.contains("then"))
                || (lower.contains("all") && lower.contains("accounts"))
                || lower.contains("for each account");
    }
}
