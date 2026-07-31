package com.nexus.account.unit;

import com.nexus.account.domain.exception.*;
import com.nexus.account.web.advice.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(new SimpleMeterRegistry());
    }

    @Test
    void insufficientFundsReturns422() {
        ResponseEntity<ProblemDetail> response = handler.handleInsufficientFunds(
                new InsufficientFundsException("Insufficient funds"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void accountFrozenReturns422() {
        ResponseEntity<ProblemDetail> response = handler.handleAccountFrozen(
                new AccountFrozenException("frozen"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("ACCOUNT_FROZEN");
    }

    @Test
    void dailyLimitExceededReturns422() {
        ResponseEntity<ProblemDetail> response = handler.handleDailyLimitExceeded(
                new DailyLimitExceededException("daily limit"));

        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void monthlyLimitExceededReturns422() {
        ResponseEntity<ProblemDetail> response = handler.handleMonthlyLimitExceeded(
                new MonthlyLimitExceededException("monthly limit"));

        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("MONTHLY_LIMIT_EXCEEDED");
    }

    @Test
    void accountingIntegrityViolationReturns500WithoutLeakingDetails() {
        ResponseEntity<ProblemDetail> response = handler.handleAccountingIntegrity(
                new AccountingIntegrityException("CRITICAL: negative balance detected accountId=xyz"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).doesNotContain("xyz");
    }

    @Test
    void lockTimeoutReturns409WithRetryAfterHeader() {
        ResponseEntity<ProblemDetail> response = handler.handleLockTimeout(
                new CannotAcquireLockException("timeout"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void optimisticLockingReturns409() {
        ResponseEntity<ProblemDetail> response = handler.handleOptimisticLocking(
                new OptimisticLockingFailureException("stale"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void dataIntegrityViolationForDuplicateReservationReturns409() {
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("violates constraint uq_active_reservation"));

        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("DUPLICATE_RESERVATION");
    }

    @Test
    void dataIntegrityViolationGenericFallback() {
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("some other constraint"));

        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("DATA_INTEGRITY_VIOLATION");
    }

    @Test
    void unauthorizedReturns401() {
        ResponseEntity<ProblemDetail> response = handler.handleUnauthorized(
                new UnauthorizedException("no header"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void accessDeniedReturns403() {
        ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(
                new AccessDeniedException("not yours"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountNotFoundReturns404() {
        ResponseEntity<ProblemDetail> response = handler.handleAccountNotFound(
                new AccountNotFoundException("not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void illegalArgumentReturns400() {
        ResponseEntity<ProblemDetail> response = handler.handleIllegalArgument(
                new IllegalArgumentException("bad amount"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void illegalStateReturns422() {
        ResponseEntity<ProblemDetail> response = handler.handleIllegalState(
                new IllegalStateException("cannot close"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void generalExceptionReturns500WhenNotCommitted() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);

        ResponseEntity<ProblemDetail> result = handler.handleGeneral(new RuntimeException("boom"), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void generalExceptionReturnsNullWhenAlreadyCommitted() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        ResponseEntity<ProblemDetail> result = handler.handleGeneral(new RuntimeException("boom"), response);

        assertThat(result).isNull();
    }
}
