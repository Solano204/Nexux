package com.nexus.transaction.unit;

import com.nexus.transaction.domain.exception.*;
import com.nexus.transaction.web.advice.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(new SimpleMeterRegistry());
    }

    @Test
    void notFoundReturns404() {
        ResponseEntity<ProblemDetail> response = handler.handleNotFound(
                new TransactionNotFoundException("Transaction not found: abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getTitle()).isEqualTo("Transaction Not Found");
    }

    @Test
    void invalidStateReturns409WithErrorCode() {
        ResponseEntity<ProblemDetail> response = handler.handleInvalidState(
                new InvalidTransactionStateException("Invalid transition: INITIATED -> COMPLETED"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties().get("errorCode")).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void duplicateTransactionReturns409() {
        ResponseEntity<ProblemDetail> response = handler.handleDuplicate(
                new DuplicateTransactionException("Already exists"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getTitle()).isEqualTo("Duplicate Transaction");
    }

    @Test
    void unauthorizedReturns401() {
        ResponseEntity<ProblemDetail> response = handler.handleUnauthorized(
                new UnauthorizedException("Authentication required"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void optimisticLockReturns409WithRetryAfterHeader() {
        ResponseEntity<ProblemDetail> response = handler.handleOptimisticLock(
                new OptimisticLockingFailureException("stale row"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody().getProperties().get("retryable")).isEqualTo(true);
    }

    @Test
    void dataIntegrityViolationForIdempotencyKeyReturns409WithFriendlyMessage() {
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_transactions_idempotency\""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getTitle()).isEqualTo("Duplicate Idempotency Key");
    }

    @Test
    void dataIntegrityViolationForStateTransitionReturns409() {
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("Invalid transaction state transition attempted"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getTitle()).isEqualTo("State Transition Rejected");
    }

    @Test
    void dataIntegrityViolationGenericFallsBackToGenericMessage() {
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("some other constraint"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getDetail()).isEqualTo("A data constraint was violated.");
    }

    @Test
    void generalExceptionReturns500WithGenericMessage() {
        ResponseEntity<ProblemDetail> response = handler.handleGeneral(new RuntimeException("leaked internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).doesNotContain("leaked internal detail");
    }
}
