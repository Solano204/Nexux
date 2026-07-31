package com.nexus.identity.unit;

import com.nexus.identity.application.command.UnauthorizedException;
import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.web.controller.UserController;
import com.nexus.identity.web.dto.request.ChangePasswordRequest;
import com.nexus.identity.web.dto.response.SessionSummaryResponse;
import com.nexus.identity.web.dto.response.UserProfileResponse;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock private UserCommandService commandService;
    @Mock private UserQueryService queryService;
    @Mock private Tracer tracer;
    @Mock private HttpServletRequest request;

    private UserController controller;
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new UserController(commandService, queryService, tracer);
    }

    @Test
    void getMyProfileReturnsProfileForAuthenticatedUser() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        UserProfileResponse profile = new UserProfileResponse(
                USER_ID.toString(), "user@example.com", "+521", "Jane", "1990-01-01",
                "ACTIVE", List.of("USER"), true, null, "2024-01-01T00:00:00Z");
        when(queryService.getUserProfile(USER_ID)).thenReturn(profile);

        ResponseEntity<UserProfileResponse> response = controller.getMyProfile(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().userId()).isEqualTo(USER_ID.toString());
    }

    @Test
    void getMyProfileThrowsWhenHeaderMissing() {
        when(request.getHeader("X-User-Id")).thenReturn(null);

        assertThatThrownBy(() -> controller.getMyProfile(request))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(queryService);
    }

    @Test
    void getMySessionsDelegatesToQueryService() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        SessionSummaryResponse session = new SessionSummaryResponse(
                UUID.randomUUID().toString(), "1.2.3.4", "fp", "now", "now");
        when(queryService.getActiveSessions(USER_ID)).thenReturn(List.of(session));

        ResponseEntity<List<SessionSummaryResponse>> response = controller.getMySessions(request);

        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void terminateSessionReturnsNoContent() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        UUID sessionId = UUID.randomUUID();

        ResponseEntity<Void> response = controller.terminateSession(sessionId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(commandService).terminateSession(USER_ID, sessionId);
    }

    @Test
    void changePasswordDelegatesWithClientIpAndSessionId() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        when(request.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9, 1.1.1.1");
        UUID sessionId = UUID.randomUUID();
        when(request.getAttribute("currentSessionId")).thenReturn(sessionId);

        ChangePasswordRequest req = new ChangePasswordRequest("old-pw", "NewSup3rSecret!Pass");

        ResponseEntity<Void> response = controller.changePassword(req, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(commandService).changePassword(eq(USER_ID), eq(req), eq(sessionId), eq("9.9.9.9"), anyString());
    }

    @Test
    void changePasswordFallsBackToRemoteAddrWhenNoForwardedHeader() {
        when(request.getHeader("X-User-Id")).thenReturn(USER_ID.toString());
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getAttribute("currentSessionId")).thenReturn(null);

        ChangePasswordRequest req = new ChangePasswordRequest("old-pw", "NewSup3rSecret!Pass");

        controller.changePassword(req, request);

        verify(commandService).changePassword(eq(USER_ID), eq(req), isNull(), eq("127.0.0.1"), anyString());
    }
}
