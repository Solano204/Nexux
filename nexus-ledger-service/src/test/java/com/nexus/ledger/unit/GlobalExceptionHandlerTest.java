package com.nexus.ledger.unit;

import com.nexus.ledger.domain.exception.*;
import com.nexus.ledger.web.advice.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unauthorizedReturns401WithErrorCode() {
        ResponseEntity<ProblemDetail> response = handler.handleUnauthorized(
                new UnauthorizedException("Authentication required"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void accessDeniedReturns403() {
        ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(
                new AccessDeniedException("Account does not belong to requesting user"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void accountNotFoundReturns404() {
        ResponseEntity<ProblemDetail> response = handler.handleAccountNotFound(
                new AccountNotFoundException("Account not found: abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void generalExceptionReturns500WhenResponseNotCommitted() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);

        ResponseEntity<ProblemDetail> result = handler.handleGeneral(new RuntimeException("boom"), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody().getDetail()).doesNotContain("boom");
    }

    @Test
    void generalExceptionReturnsNullWhenResponseAlreadyCommitted() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        ResponseEntity<ProblemDetail> result = handler.handleGeneral(new RuntimeException("SSE already streaming"), response);

        assertThat(result).isNull();
    }
}
