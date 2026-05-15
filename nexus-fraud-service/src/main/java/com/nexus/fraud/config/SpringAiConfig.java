package com.nexus.fraud.config;

import com.nexus.fraud.agent.tools.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.*;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postprocessing.RerankPostProcessor;
import org.springframework.ai.rag.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.query.retrieval.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring AI Configuration — Fraud Service.
 *
 * Configures three ChatClient instances:
 * 1. planningClient  — Plan phase (low temperature, structured output)
 * 2. agentClient     — Tool execution loop (Section 11 ReAct)
 * 3. synthesisClient — Final FraudDecision synthesis
 *
 * All use temperature=0.1 — fraud decisions must be reproducible.
 *
 * Advisor chain order (applied bottom-up in execution):
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
        - Risk score 0-29:  APPROVE — consistent with user history
        - Risk score 30-69: REVIEW  — ambiguous signals, human review needed
        - Risk score 70-100: REJECT — clear fraud indicators

        MANDATORY RULES:
        - velocity_check_tool MUST be called on EVERY transaction
        - rag_policy_tool MUST be called on EVERY transaction
        - Never approve a transaction with impossible_travel=true without REVIEW
        - Never approve a blacklisted merchant transaction
        - Cite specific policy sections in every rejection

        RESPONSE FORMAT:
        - Always respond ONLY with valid JSON matching the FraudDecision schema
        - Never add prose before or after the JSON
        - Always populate all fields — null is not acceptable for decision fields
        - Confidence < 0.6 should escalate to REVIEW

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
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat("json_object"))
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
                        // Tool calling enabled — but WE drive the loop
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
                        // Prevent injection from transaction description field
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
     * RAG advisor retrieves policy context one final time.
     * Memory advisor provides compliance review session context.
     */
    @Bean("fraudSynthesisClient")
    public ChatClient fraudSynthesisClient(
            OpenAiChatModel model,
            PgVectorStore policyVectorStore,
            InMemoryChatMemory complianceMemory) {

        // Section 10: Advanced RAG pipeline for policy retrieval
        RetrievalAugmentationAdvisor ragAdvisor =
                RetrievalAugmentationAdvisor.builder()
                        .queryExpander(
                                MultiQueryExpander.builder()
                                        .chatClientBuilder(ChatClient.builder(model))
                                        .numberOfQueries(4)
                                        .build())
                        .documentRetriever(
                                VectorStoreDocumentRetriever.builder()
                                        .vectorStore(policyVectorStore)
                                        .topK(15)
                                        .similarityThreshold(0.65)
                                        .build())
                        .documentPostProcessors(
                                new RerankPostProcessor(6),
                                new CitationHeaderPostProcessor()
                        )
                        .queryAugmenter(
                                ContextualQueryAugmenter.builder()
                                        .allowEmptyContext(true)
                                        .build())
                        .build();

        // Section 7: Memory for compliance review sessions
        MessageChatMemoryAdvisor memoryAdvisor =
                MessageChatMemoryAdvisor.builder(complianceMemory)
                        .build();

        return ChatClient.builder(model)
                .defaultSystem(FRAUD_SYSTEM_PROMPT + """

                SYNTHESIS INSTRUCTIONS:
                Analyze ALL tool results provided in the conversation.
                Produce a complete FraudDecision JSON.

                Scoring guide:
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

                Always cite specific policy sections.
                Return ONLY valid JSON. No preamble.
                """)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.1)
                        .maxTokens(3000)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat("json_object"))
                        .build())
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        memoryAdvisor,
                        ragAdvisor
                )
                .build();
    }

    @Bean
    public InMemoryChatMemory complianceMemory() {
        return new InMemoryChatMemory();
    }

    /**
     * Citation header post-processor for policy documents.
     * Prepends [policy: X, section: Y] to each retrieved fragment.
     */
    static class CitationHeaderPostProcessor implements
            org.springframework.ai.rag.postprocessing.DocumentPostProcessor {

        @Override
        public List<org.springframework.ai.document.Document> process(
                List<org.springframework.ai.document.Document> docs) {
            return docs.stream().map(doc -> {
                var meta = doc.getMetadata();
                String header = String.format(
                        "[policy: %s, section: %s] ",
                        meta.getOrDefault("policy_title", "Unknown"),
                        meta.getOrDefault("section", "Unknown"));
                return new org.springframework.ai.document.Document(
                        header + doc.getContent(), doc.getMetadata());
            }).toList();
        }
    }
}