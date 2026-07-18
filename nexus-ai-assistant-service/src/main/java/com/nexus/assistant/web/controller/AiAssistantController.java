package com.nexus.assistant.web.controller;

import com.nexus.assistant.application.ChatService;
import com.nexus.assistant.application.DocumentAnalysisService;
import com.nexus.assistant.domain.exception.UnauthorizedException;
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
public class AiAssistantController {

    private final ChatService chatService;
    private final DocumentAnalysisService documentAnalysisService;

    /**
     * Main streaming chat endpoint — Section 3 SSE.
     * Returns Flux<String> as text/event-stream.
     */
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

    /**
     * Document analysis endpoint — Section 8 multimodal.
     * Accepts image upload + text question.
     */
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