package com.nexus.assistant.web.controller;

import com.nexus.assistant.application.DocumentAnalysisService;
import com.nexus.assistant.domain.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Document Analysis Controller — Multimodal endpoint (Section 8).
 *
 * POST /api/v1/ai/documents/analyze — Upload bill/receipt + question
 * Vision model extracts financial data, primary client interprets.
 */
@RestController
@RequestMapping("/api/v1/ai/documents")
@RequiredArgsConstructor
public class DocumentAnalysisController {

    private final DocumentAnalysisService documentAnalysisService;

    @PostMapping(
            value = "/analyze",
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
                (sessionId != null ? sessionId : UUID.randomUUID().toString());

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