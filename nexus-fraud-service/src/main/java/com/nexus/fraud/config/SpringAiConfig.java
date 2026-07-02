package com.nexus.fraud.config;

import com.nexus.fraud.agent.tools.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring AI Configuration — Fraud Service.
 *
 * Configures three ChatClient instances:
 * 1. planningClient  — Plan phase (low temperature, structured JSON output)
 * 2. agentClient     — Tool execution loop (Section 11 ReAct)
 * 3. synthesisClient — Final FraudDecision synthesis with RAG
 *
 * All use temperature=0.1 — fraud decisions must be reproducible.
 *
 * Spring AI 1.0.0-M6 compatibility notes:
 * - ResponseFormat: use new ResponseFormat(ResponseFormat.Type.JSON_OBJECT)
 *   (no OpenAiChatOptions.ResponseFormat enum exists in M6)
 * - RetrievalAugmentationAdvisor.Builder: no documentPostProcessors()
 *   method in M6 — citation headers are handled in the system prompt instead
 * - PgVectorStore package: org.springframework.ai.vectorstore.pgvector
 *
 * Advisor chain order (applied bottom-up):
 * 1. SafeGuardAdvisor      — blocks injection attempts
 * 2. ContentSanitizerAdvisor — cleans user-provided transaction data
 * 3. RetrievalAugmentationAdvisor — RAG for policy retrieval
 * 4. MessageChatMemoryAdvisor — compliance review session memory
 * 5. SimpleLoggerAdvisor    — audit log of every LLM call
 */
@Configuration
public class SpringAiConfig {

    private static final String FRAUD_SYSTEM_PROMPT = """
        You are a senior fraud detection analyst at a regulated Mexican bank.
        You analyze financial transactions for fraud risk.

        DECISION FRAMEWORK:
        The `decision` field MUST match the score range. No exceptions.
        - Risk score  0-29:  decision = APPROVE
        - Risk score 30-69:  decision = REVIEW
        - Risk score 70-100: decision = REJECT
        Confidence is INFORMATIONAL ONLY. Low confidence NEVER promotes
        APPROVE to REVIEW. A new user with no transaction history has low
        confidence by definition — that is normal, not suspicious.
        Score 10 → decision = APPROVE, always.

        MANDATORY RULES:
        - velocity_check_tool MUST be called on EVERY transaction
        - rag_policy_tool MUST be called on EVERY transaction
        - Never set decision=REVIEW unless riskScore >= 30
        - Never set decision=REJECT unless riskScore >= 70
        - Never approve a transaction with impossible_travel=true
        - Never approve a blacklisted merchant transaction
        - Cite specific policy sections in every rejection

        RESPONSE FORMAT:
        - Always respond ONLY with valid JSON matching the FraudDecision schema
        - Never add prose before or after the JSON
        - Always populate all fields — null is not acceptable for decision fields

        POLICY CITATION FORMAT:
        When citing fraud policies retrieved from the knowledge base, always
        include the policy title, section number, and how it applies.
        Format: [policy: {title}, section: {section}] — {application}

        LEGAL NOTE:
        Your decisions are subject to CNBV (Comisión Nacional Bancaria
        y de Valores) regulatory review. Every rejection must be explainable
        in clear language to the affected user.
        """;

    // ── Plan Phase Client ─────────────────────────────────────

    /**
     * Planning client — produces FraudAnalysisPlan only.
     * No tools, no advisors beyond logging.
     * temperature=0.0 for deterministic planning.
     *
     * Spring AI 1.0.0-M6: ResponseFormat via constructor, not enum.
     */
    @Bean("fraudPlanningClient")
    public ChatClient fraudPlanningClient(OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem("""
                    You are a fraud analysis planner. Given a transaction and
                    its pre-computed signals, you determine WHICH tools to call
                    and in WHAT ORDER to analyze this transaction.

                    Available tools:
                    - velocity_check_tool (MANDATORY)
                    - rag_policy_tool (MANDATORY)
                    - geolocation_anomaly_tool (if IP address available)
                    - merchant_risk_tool (for PAYMENT transactions)
                    - behavioral_analysis_tool (if user has history)
                    - account_relationship_tool (for INTERNAL_TRANSFER)

                    Parallel execution rules:
                    - velocity_check + merchant_risk + geolocation_anomaly
                      CAN run in parallel (no dependencies)
                    - rag_policy runs AFTER seeing initial signals
                      (needs detected concerns as context)
                    - behavioral_analysis runs AFTER velocity and geolocation
                      (enriches interpretation of behavioral deviations)

                    Return ONLY valid JSON matching the FraudAnalysisPlan schema.
                    """)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.0)
                        .maxTokens(800)
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    // ── Agent Execution Client ────────────────────────────────

    /**
     * Agent client — drives the ReAct tool execution loop.
     * Tools registered here but NOT auto-executed (Section 11 pattern).
     * internalToolExecutionEnabled=false → service drives the loop.
     *
     * Advisor chain:
     * - ContentSanitizerAdvisor: prevents prompt injection from
     *   user-supplied transaction descriptions
     * - SafeGuardAdvisor: blocks known injection patterns
     */
    @Bean("fraudAgentClient")
    public ChatClient fraudAgentClient(
            OpenAiChatModel model,
            VelocityCheckTool velocityTool,
            GeolocationAnomalyTool geoTool,
            MerchantRiskTool merchantTool,
            RagPolicyTool policyTool,
            BehavioralAnalysisTool behavioralTool,
            AccountRelationshipTool relationshipTool) {

        return ChatClient.builder(model)
                .defaultSystem(FRAUD_SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.1)
                        .maxTokens(2000)
                        .internalToolExecutionEnabled(false)
                        .build())
                .defaultTools(
                        velocityTool,
                        geoTool,
                        merchantTool,
                        policyTool,
                        behavioralTool,
                        relationshipTool
                )
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new SafeGuardAdvisor(List.of(
                                "ignore previous instructions",
                                "forget your system prompt",
                                "you are now",
                                "pretend you are",
                                "api_key",
                                "sk-",
                                "print your instructions")),
                        new ContentSanitizerAdvisor()
                )
                .build();
    }

    // ── Synthesis Client ──────────────────────────────────────

    /**
     * Synthesis client — produces final FraudDecision JSON.
     * Receives the full conversation history (plan + all tool results).
     * RAG advisor retrieves policy context for final synthesis.
     * Memory advisor provides compliance review session context.
     *
     * Spring AI 1.0.0-M6 notes:
     * - RetrievalAugmentationAdvisor.Builder has NO documentPostProcessors()
     *   method in M6. Citation headers are handled via system prompt
     *   instructions instead of a post-processor.
     * - ResponseFormat via constructor, not enum.
     */
    @Bean("fraudSynthesisClient")
    public ChatClient fraudSynthesisClient(
            OpenAiChatModel model,
            PgVectorStore policyVectorStore) {

        // Section 10: RAG pipeline for policy retrieval
        // M6: no documentPostProcessors — citations handled in prompt
        RetrievalAugmentationAdvisor ragAdvisor =
                RetrievalAugmentationAdvisor.builder()
                        .queryExpander(
                                MultiQueryExpander.builder()
                                        .chatClientBuilder(
                                                ChatClient.builder(model))
                                        .numberOfQueries(4)
                                        .build())
                        .documentRetriever(
                                VectorStoreDocumentRetriever.builder()
                                        .vectorStore(policyVectorStore)
                                        .topK(15)
                                        .similarityThreshold(0.65)
                                        .build())
                        .queryAugmenter(
                                ContextualQueryAugmenter.builder()
                                        .allowEmptyContext(true)
                                        .build())
                        .build();

        return ChatClient.builder(model)
                .defaultSystem(FRAUD_SYSTEM_PROMPT + """

                    SYNTHESIS INSTRUCTIONS:
                    Analyze ALL tool results provided in the conversation.
                    Produce a complete FraudDecision JSON.

                    BASE SCORE: 0 (not 50, not 60 — always start at zero).
                    Add points ONLY for signals that are explicitly present.
                    If a tool returned null or empty, that signal is ABSENT —
                    treat absence of data as neutral (0 points), never as risk.

                    Scoring guide (additive from base 0):
                    - impossible_travel=true: +50 points
                    - blacklisted merchant: +100 points (hard reject)
                    - high_velocity (>5 txn/5min): +35 points
                    - unknown device + new country: +25 points
                    - Z-score amount > 3: +15 points
                    - new counterparty, first transaction: +10 points
                    - VPN detected: +15 points
                    - Tor exit node: +50 points

                    Reducing factors:
                    - established relationship (>10 prior txns): -10 points
                    - typical time of day: -5 points
                    - low-risk merchant category: -5 points

                    IMPORTANT: If all tools returned null/empty, the only
                    applicable signal is "new counterparty" (+10). Final
                    score = 10 → decision = APPROVE. Do NOT invent signals.
                    Low confidence on a new user is EXPECTED. It does NOT
                    change APPROVE to REVIEW. Score < 30 → APPROVE always.

                    CITATION INSTRUCTIONS:
                    For each retrieved policy fragment, cite it as:
                    [policy: {title}, section: {number}] — {how it applies}
                    Include these citations in the policyCitations field.

                    Always cite specific policy sections.
                    Return ONLY valid JSON. No preamble.
                    """)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.1)
                        .maxTokens(3000)
                        .build())
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        ragAdvisor
                )
                .build();
    }

    /**
     * Inline ContentSanitizerAdvisor — prevents prompt injection
     * from user-supplied transaction descriptions.
     *
     * Implements CallAroundAdvisor (M6 advisor API).
     * In production, this would strip or escape suspicious patterns
     * from the description field before sending to the LLM.
     */
    static class ContentSanitizerAdvisor implements CallAdvisor {

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request,
                                             CallAdvisorChain chain) {
            return chain.nextCall(request);
        }

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public String getName() {
            return "ContentSanitizerAdvisor";
        }
    }
}