package com.nexus.identity.unit;

import com.nexus.identity.application.command.UnauthorizedException;
import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.web.controller.KycController;
import com.nexus.identity.web.dto.response.KycInitiationResponse;
import com.nexus.identity.web.dto.response.KycStatusResponse;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycControllerTest {

    @Mock private UserCommandService commandService;
    @Mock private UserQueryService queryService;
    @Mock private Tracer tracer;
    @Mock private HttpServletRequest request;

    private KycController controller;
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new KycController(commandService, queryService, tracer);
    }

    @Test
    void initiateKycReturns202WithVerificationId() throws Exception {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        MockMultipartFile document = new MockMultipartFile("document", "id.jpg", "image/jpeg", new byte[]{1});
        KycInitiationResponse mockResponse = new KycInitiationResponse(UUID.randomUUID().toString(), "started");
        when(commandService.initiateKyc(eq(USER_ID), eq(document), eq("PASSPORT"),
                eq("Jane Doe"), eq("1990-01-01"), eq("X123"), isNull(), isNull(),
                any(), anyString())).thenReturn(mockResponse);

        ResponseEntity<KycInitiationResponse> response = controller.initiateKyc(
                document, "PASSPORT", "Jane Doe", "1990-01-01", "X123", null, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(mockResponse);
    }

    @Test
    void initiateKycThrowsWhenUnauthenticated() {
        when(request.getHeader("X-User-Id")).thenReturn(null);
        MockMultipartFile document = new MockMultipartFile("document", "id.jpg", "image/jpeg", new byte[]{1});

        assertThatThrownBy(() -> controller.initiateKyc(
                document, "PASSPORT", "Jane Doe", "1990-01-01", "X123", null, null, request))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(commandService);
    }

    @Test
    void getKycStatusDelegatesToQueryService() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        KycStatusResponse status = new KycStatusResponse(null, "NOT_STARTED", null, null, null);
        when(queryService.getCurrentKycStatus(USER_ID)).thenReturn(status);

        ResponseEntity<KycStatusResponse> response = controller.getKycStatus(request);

        assertThat(response.getBody().decision()).isEqualTo("NOT_STARTED");
    }
}
