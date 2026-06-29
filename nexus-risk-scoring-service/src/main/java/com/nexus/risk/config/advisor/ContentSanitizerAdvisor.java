package com.nexus.risk.config.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Sanitizes requests and responses passing through the risk execution client.
 *
 * REQUEST  — strips null bytes, control characters, and Unicode directional
 *            overrides (prompt-injection vectors).
 *
 * RESPONSE — strips markdown code fences that GPT-4o sometimes wraps around
 *            JSON despite instructions not to. Without this, Jackson fails to
 *            deserialise entity() calls.
 *
 * Order 100 — runs before ErrorWrappingAdvisor (order 200).
 */
@Slf4j
public class ContentSanitizerAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Pattern CODE_FENCE =
            Pattern.compile("```(?:json)?\\s*(.*?)\\s*```",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern DANGEROUS_UNICODE =
            Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]");

    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    @Override
    public String getName() { return "ContentSanitizerAdvisor"; }

    @Override
    public int getOrder() { return 100; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest sanitized = sanitizeRequest(request);
        ChatClientResponse response = chain.nextCall(sanitized);
        return sanitizeResponse(response);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest sanitized = sanitizeRequest(request);
        return chain.nextStream(sanitized).map(this::sanitizeResponse);
    }

    private ChatClientRequest sanitizeRequest(ChatClientRequest request) {
        try {
            Prompt prompt = request.prompt();
            var sanitizedMessages = prompt.getInstructions().stream()
                    .map(msg -> {
                        if (msg instanceof UserMessage um) {
                            String clean = sanitizeText(um.getText());
                            if (!clean.equals(um.getText())) {
                                log.debug("ContentSanitizer: cleaned request message");
                                return (org.springframework.ai.chat.messages.Message)
                                        new UserMessage(clean);
                            }
                        }
                        return msg;
                    })
                    .toList();

            Prompt newPrompt = new Prompt(sanitizedMessages, prompt.getOptions());
            return request.mutate().prompt(newPrompt).build();

        } catch (Exception e) {
            log.warn("ContentSanitizer: request sanitization failed, passing through: {}",
                    e.getMessage());
            return request;
        }
    }

    private String sanitizeText(String input) {
        if (input == null) return "";
        String r = DANGEROUS_UNICODE.matcher(input).replaceAll("");
        return CONTROL_CHARS.matcher(r).replaceAll("");
    }

    private ChatClientResponse sanitizeResponse(ChatClientResponse response) {
        try {
            ChatResponse chatResponse = response.chatResponse();
            if (chatResponse == null) return response;

            boolean modified = false;
            List<Generation> cleaned = new ArrayList<>();

            for (Generation gen : chatResponse.getResults()) {
                String text = gen.getOutput().getText();
                if (text == null) { cleaned.add(gen); continue; }

                String stripped = stripCodeFences(text);
                if (!stripped.equals(text)) {
                    log.debug("ContentSanitizer: stripped code fences from response");
                    modified = true;
                    cleaned.add(new Generation(
                            new AssistantMessage(stripped), gen.getMetadata()));
                } else {
                    cleaned.add(gen);
                }
            }

            if (!modified) return response;

            ChatResponse cleanedChatResponse =
                    new ChatResponse(cleaned, chatResponse.getMetadata());

            return ChatClientResponse.builder()
                    .chatResponse(cleanedChatResponse)
                    .context(response.context())
                    .build();

        } catch (Exception e) {
            log.warn("ContentSanitizer: response sanitization failed, passing through: {}",
                    e.getMessage());
            return response;
        }
    }

    private String stripCodeFences(String input) {
        if (input == null) return "";
        var m = CODE_FENCE.matcher(input.trim());
        return m.find() ? m.group(1).trim() : input;
    }
}
