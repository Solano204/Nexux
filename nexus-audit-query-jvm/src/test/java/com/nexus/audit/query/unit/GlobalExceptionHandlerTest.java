package com.nexus.audit.query.unit;

import com.nexus.audit.query.domain.exception.ForbiddenException;
import com.nexus.audit.query.domain.exception.UnauthorizedException;
import com.nexus.audit.query.web.advice.GlobalExceptionHandler;
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
    void unauthorizedReturns401() {
        ResponseEntity<ProblemDetail> response = handler.handleUnauthorized(
                new UnauthorizedException("Authentication required"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void forbiddenReturns403() {
        ResponseEntity<ProblemDetail> response = handler.handleForbidden(
                new ForbiddenException("COMPLIANCE_OFFICER or ADMIN role required"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("FORBIDDEN");
    }

    @Test
    void generalExceptionReturns500WhenNotCommitted() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);

        ResponseEntity<ProblemDetail> result = handler.handleGeneral(new RuntimeException("es outage"), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody().getDetail()).doesNotContain("es outage");
    }

    @Test
    void generalExceptionReturnsNullWhenAlreadyCommitted() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        ResponseEntity<ProblemDetail> result = handler.handleGeneral(new RuntimeException("boom"), response);

        assertThat(result).isNull();
    }
}
