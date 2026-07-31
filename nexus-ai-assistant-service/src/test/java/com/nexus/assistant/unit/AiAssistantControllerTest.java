package com.nexus.assistant.unit;

import com.nexus.assistant.application.ChatService;
import com.nexus.assistant.application.DocumentAnalysisService;
import com.nexus.assistant.domain.exception.UnauthorizedException;
import com.nexus.assistant.web.controller.AiAssistantController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiAssistantControllerTest {

    @Mock private ChatService chatService;
    @Mock private DocumentAnalysisService documentAnalysisService;
    @Mock private HttpServletRequest request;

    private AiAssistantController controller;

    @BeforeEach
    void setUp() {
        controller = new AiAssistantController(chatService, documentAnalysisService);
    }

    @Test
    void chatThrowsUnauthorizedWhenUserIdHeaderMissing() {
        when(request.getHeader("X-User-Id")).thenReturn(null);

        assertThatThrownBy(() -> controller.chat(Map.of("message", "hi"), request))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(chatService);
    }

    @Test
    void chatDelegatesWithMessageAndGeneratesSessionIdWhenMissing() {
        when(request.getHeader("X-User-Id")).thenReturn("user-1");
        when(chatService.chat(anyString(), anyString(), anyString())).thenReturn(Flux.just("hola"));

        Flux<String> result = controller.chat(Map.of("message", "cual es mi saldo"), request);

        StepVerifier.create(result).expectNext("hola").verifyComplete();

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService).chat(eq("cual es mi saldo"), eq("user-1"), sessionCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(sessionCaptor.getValue()).isNotBlank();
    }

    @Test
    void chatReusesProvidedSessionId() {
        when(request.getHeader("X-User-Id")).thenReturn("user-1");
        when(chatService.chat(anyString(), anyString(), anyString())).thenReturn(Flux.empty());

        controller.chat(Map.of("message", "hola", "sessionId", "session-abc"), request);

        verify(chatService).chat(anyString(), eq("user-1"), eq("session-abc"));
    }

    @Test
    void analyzeDocumentThrowsUnauthorizedWhenUserIdMissing() {
        when(request.getHeader("X-User-Id")).thenReturn(null);
        MockMultipartFile file = new MockMultipartFile("file", "receipt.jpg", "image/jpeg", new byte[]{1});

        assertThatThrownBy(() -> controller.analyzeDocument(file, "what is this?", null, request))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(documentAnalysisService);
    }

    @Test
    void analyzeDocumentBuildsConversationIdFromUserAndSession() throws Exception {
        when(request.getHeader("X-User-Id")).thenReturn("user-1");
        when(documentAnalysisService.analyzeAndRespond(any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("this is a receipt"));
        MockMultipartFile file = new MockMultipartFile("file", "receipt.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Flux<String> result = controller.analyzeDocument(file, "what is this?", "session-xyz", request);

        StepVerifier.create(result).expectNext("this is a receipt").verifyComplete();
        verify(documentAnalysisService).analyzeAndRespond(any(), eq("image/jpeg"), eq("what is this?"), eq("user-1:session-xyz"));
    }
}
