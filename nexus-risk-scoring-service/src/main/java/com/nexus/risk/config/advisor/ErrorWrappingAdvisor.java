package com.nexus.risk.config.advisor;

import com.nexus.risk.domain.exception.RiskScoringException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * Wraps any raw Spring AI / HTTP exception into RiskScoringException so
 * the rest of the application always catches a known type.
 *
 * Also guards against null / empty model responses, throwing immediately
 * rather than letting a NullPointerException surface inside entity()
 * deserialisation.
 *
 * Order 200 — runs after ContentSanitizerAdvisor (100).
 */
@Slf4j
public class ErrorWrappingAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() { return "ErrorWrappingAdvisor"; }

    @Override
    public int getOrder() { return 200; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        try {
            ChatClientResponse response = chain.nextCall(request);
            validateResponse(response);
            return response;

        } catch (RiskScoringException rse) {
            throw rse;

        } catch (Exception e) {
            String msg = buildMessage(request, e);
            log.error("ErrorWrappingAdvisor: {}", msg, e);
            throw new RiskScoringException(msg, e);
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request)
                .doOnNext(this::validateResponse)
                .onErrorMap(e -> {
                    if (e instanceof RiskScoringException) return e;
                    String msg = buildMessage(request, e);
                    log.error("ErrorWrappingAdvisor (stream): {}", msg, e);
                    return new RiskScoringException(msg, e);
                });
    }

    private void validateResponse(ChatClientResponse response) {
        if (response == null) {
            throw new RiskScoringException(
                    "AI model returned a null response", null);
        }
        ChatResponse cr = response.chatResponse();
        if (cr == null || cr.getResults() == null || cr.getResults().isEmpty()) {
            throw new RiskScoringException(
                    "AI model returned an empty response with no generations", null);
        }
        var first = cr.getResults().get(0);
        if (first == null || first.getOutput() == null) {
            throw new RiskScoringException(
                    "AI model generation had null output", null);
        }
    }

    private String buildMessage(ChatClientRequest request, Throwable cause) {
        String hint = "";
        try {
            var msgs = request.prompt().getInstructions();
            if (msgs != null && !msgs.isEmpty()) {
                String text = msgs.get(msgs.size() - 1).getText();
                if (text != null) {
                    hint = " | prompt-hint: " +
                            text.substring(0, Math.min(120, text.length()))
                                    .replaceAll("\\s+", " ");
                }
            }
        } catch (Exception ignored) { /* best-effort */ }

        return "AI call failed: " + cause.getClass().getSimpleName()
                + ": " + cause.getMessage() + hint;
    }
}
