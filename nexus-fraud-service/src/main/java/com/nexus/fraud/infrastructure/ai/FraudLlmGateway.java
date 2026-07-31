package com.nexus.fraud.infrastructure.ai;

import com.nexus.fraud.domain.model.FraudAnalysisPlan;
import com.nexus.fraud.domain.model.FraudDecision;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fraud LLM Gateway - the three OpenAI call sites used by FraudReActAgent
 * (plan / tool-execution / synthesis), all guarded by the same
 * "openai" circuit breaker instance (resilience4j.circuitbreaker.instances.openai
 * in application.yml), plus (added - see
 * CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md Fase 2) a
 * matching "openai" rate limiter instance. CircuitBreaker only reacts to
 * sustained failures; it does nothing to stop concurrent fraud analyses
 * from bursting past OpenAI's per-minute limit in the first place -
 * nexus-risk-scoring-service already hit exactly that in production
 * ("Limit 30000, Used 30000") before it got a client-side limiter.
 * Annotation order here matters: with no @Retry/@Bulkhead present,
 * Resilience4j's default aspect order applies CircuitBreaker outside
 * RateLimiter, so an already-open circuit fails fast without consuming a
 * rate-limit permit.
 *
 * Lives in its own bean so @CircuitBreaker/@RateLimiter actually apply:
 * FraudReActAgent calls these through the Spring proxy for THIS bean, not
 * as a private self-invoked method, which would silently skip the AOP
 * interceptor. FraudReActAgent keeps its existing try/catch fallbacks
 * around each call - this only adds fast-fail + state metrics once OpenAI
 * is unhealthy or throttled.
 */
@Component
public class FraudLlmGateway {

    private final ChatClient planningClient;
    private final ChatClient agentClient;
    private final ChatClient synthesisClient;

    public FraudLlmGateway(
            @Qualifier("fraudPlanningClient") ChatClient planningClient,
            @Qualifier("fraudAgentClient") ChatClient agentClient,
            @Qualifier("fraudSynthesisClient") ChatClient synthesisClient) {
        this.planningClient = planningClient;
        this.agentClient = agentClient;
        this.synthesisClient = synthesisClient;
    }

    @CircuitBreaker(name = "openai")
    @RateLimiter(name = "openai")
    public FraudAnalysisPlan plan(String planningPrompt) {
        return planningClient.prompt()
                .user(planningPrompt)
                .call()
                .entity(FraudAnalysisPlan.class);
    }

    @CircuitBreaker(name = "openai")
    @RateLimiter(name = "openai")
    public ChatResponse executeTool(List<Message> history, String userMessage) {
        return agentClient.prompt()
                .messages(history)
                .user(userMessage)
                .call()
                .chatResponse();
    }

    @CircuitBreaker(name = "openai")
    @RateLimiter(name = "openai")
    public FraudDecision synthesize(List<Message> fullContext) {
        return synthesisClient.prompt()
                .messages(fullContext)
                .call()
                .entity(FraudDecision.class);
    }
}
