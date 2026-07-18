package com.nexus.assistant.web.controller;

import com.nexus.assistant.application.ChatService;
import com.nexus.assistant.application.DocumentAnalysisService;
import com.nexus.assistant.domain.exception.UnauthorizedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

/**
 * AI Assistant Controller.
 *
 * POST /api/v1/ai/chat                   → SSE streaming chat
 * POST /api/v1/ai/chat/analyze-document  → Multimodal document analysis
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Tag(name = "AI Assistant", description = "General-purpose conversational financial assistant — streaming chat and document analysis, ReAct-style agent with tool access to account/fraud/transaction data.")
@SecurityRequirement(name = "X-User-Id")
public class AiAssistantController {

    private final ChatService chatService;
    private final DocumentAnalysisService documentAnalysisService;

    @Operation(
            summary = "Chat with the AI assistant (streaming)",
            description = "Server-Sent Events — tokens arrive as generated. sessionId is optional " +
                    "(auto-generated if omitted); reuse the same one across calls to continue a " +
                    "conversation, the assistant's memory is keyed by it."
    )
    @ApiResponse(responseCode = "200", description = "text/event-stream of response chunks")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing")
    @PostMapping(
            value = "/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> chat(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        String userId = extractUserId(httpRequest);
        String message = request.get("message");
        String sessionId = request.getOrDefault(
                "sessionId", UUID.randomUUID().toString());

        return chatService.chat(message, userId, sessionId);
    }

    @Operation(
            summary = "Ask about an uploaded document (streaming)",
            description = "Multimodal — vision model extracts financial data from the image (receipt, " +
                    "bill, statement), the primary model interprets it against the message. Same SSE " +
                    "streaming as the chat endpoint."
    )
    @ApiResponse(responseCode = "200", description = "text/event-stream of response chunks")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing")
    @PostMapping(
            value = "/chat/analyze-document",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> analyzeDocument(
            @RequestPart("file") MultipartFile file,
            @RequestPart("message") String message,
            @RequestPart(value = "sessionId", required = false)
            String sessionId,
            HttpServletRequest httpRequest) throws Exception {

        String userId = extractUserId(httpRequest);
        String convId = userId + ":" +
                (sessionId != null ? sessionId : UUID.randomUUID());

        return documentAnalysisService.analyzeAndRespond(
                new ByteArrayResource(file.getBytes()),
                file.getContentType(),
                message, convId);
    }

    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null)
            throw new UnauthorizedException("Authentication required");
        return userId;
    }
}