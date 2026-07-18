package com.nexus.account.web.advice;

import com.nexus.account.domain.exception.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Instant;

/**
 * GlobalExceptionHandler — Centralized exception handling.
 *
 * Returns RFC 9457 Problem Detail responses for all error cases.
 * Each domain exception maps to a specific HTTP status and error type.
 *
 * Financial exceptions (InsufficientFunds, DailyLimit, AccountFrozen)
 * return 422 Unprocessable Entity — the request was syntactically valid
 * but violates business rules.
 *
 * Infrastructure exceptions (lock timeouts, optimistic locking)
 * return 409 Conflict or 503 Service Unavailable.
 *
 * AccountingIntegrityException is CRITICAL — returns 500 and triggers
 * immediate alerting. This should NEVER happen in production.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Counter businessRuleViolations;
    private final Counter integrityViolations;
    private final Counter lockTimeouts;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.businessRuleViolations = Counter.builder(
                        "account.exceptions.business_rule.total")
                .description("Business rule violation count")
                .register(meterRegistry);
        this.integrityViolations = Counter.builder(
                        "account.exceptions.integrity.total")
                .description("Accounting integrity violation count")
                .register(meterRegistry);
        this.lockTimeouts = Counter.builder(
                        "account.exceptions.lock_timeout.total")
                .description("Lock timeout count")
                .register(meterRegistry);
    }

    // ══════════════════════════════════════════════════════════
    // DOMAIN / BUSINESS RULE EXCEPTIONS → 422
    // ══════════════════════════════════════════════════════════

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientFunds(
            InsufficientFundsException ex) {
        businessRuleViolations.increment();
        log.warn("Insufficient funds: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Insufficient Funds");
        problem.setType(URI.create("https://nexus.com/errors/insufficient-funds"));
        problem.setProperty("errorCode", "INSUFFICIENT_FUNDS");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(problem);
    }

    @ExceptionHandler(AccountFrozenException.class)
    public ResponseEntity<ProblemDetail> handleAccountFrozen(
            AccountFrozenException ex) {
        businessRuleViolations.increment();
        log.warn("Account frozen: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Account Frozen");
        problem.setType(URI.create("https://nexus.com/errors/account-frozen"));
        problem.setProperty("errorCode", "ACCOUNT_FROZEN");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(problem);
    }

    @ExceptionHandler(DailyLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleDailyLimitExceeded(
            DailyLimitExceededException ex) {
        businessRuleViolations.increment();
        log.warn("Daily limit exceeded: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Daily Transaction Limit Exceeded");
        problem.setType(URI.create("https://nexus.com/errors/daily-limit-exceeded"));
        problem.setProperty("errorCode", "DAILY_LIMIT_EXCEEDED");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(problem);
    }

    @ExceptionHandler(MonthlyLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleMonthlyLimitExceeded(
            MonthlyLimitExceededException ex) {
        businessRuleViolations.increment();
        log.warn("Monthly limit exceeded: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Monthly Transaction Limit Exceeded");
        problem.setType(URI.create("https://nexus.com/errors/monthly-limit-exceeded"));
        problem.setProperty("errorCode", "MONTHLY_LIMIT_EXCEEDED");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(problem);
    }

    // ══════════════════════════════════════════════════════════
    // CRITICAL — ACCOUNTING INTEGRITY VIOLATION → 500
    // ══════════════════════════════════════════════════════════

    @ExceptionHandler(AccountingIntegrityException.class)
    public ResponseEntity<ProblemDetail> handleAccountingIntegrity(
            AccountingIntegrityException ex) {
        integrityViolations.increment();
        log.error("CRITICAL: Accounting integrity violation: {}",
                ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "A critical accounting error has occurred. " +
                        "This incident has been logged for immediate review.");
        problem.setTitle("Accounting Integrity Error");
        problem.setType(URI.create("https://nexus.com/errors/accounting-integrity"));
        problem.setProperty("errorCode", "ACCOUNTING_INTEGRITY_VIOLATION");
        problem.setProperty("timestamp", Instant.now().toString());
        // Do NOT expose internal details in the response
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem);
    }

    // ══════════════════════════════════════════════════════════
    // INFRASTRUCTURE EXCEPTIONS
    // ══════════════════════════════════════════════════════════

    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ProblemDetail> handleLockTimeout(
            CannotAcquireLockException ex) {
        lockTimeouts.increment();
        log.warn("Lock timeout: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Account is currently being modified by another operation. " +
                        "Please retry in a moment.");
        problem.setTitle("Lock Contention");
        problem.setType(URI.create("https://nexus.com/errors/lock-contention"));
        problem.setProperty("errorCode", "LOCK_TIMEOUT");
        problem.setProperty("retryable", true);
        problem.setProperty("retryAfterSeconds", 1);
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLocking(
            OptimisticLockingFailureException ex) {
        log.warn("Optimistic locking failure: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Account was modified by another operation. Please retry.");
        problem.setTitle("Concurrent Modification");
        problem.setType(URI.create("https://nexus.com/errors/concurrent-modification"));
        problem.setProperty("errorCode", "OPTIMISTIC_LOCK_FAILURE");
        problem.setProperty("retryable", true);
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(
            DataIntegrityViolationException ex) {
        log.error("Data integrity violation: {}", ex.getMessage());

        // Check for unique constraint on (account_id, transaction_id)
        String message = ex.getMessage();
        if (message != null && message.contains("uq_active_reservation")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "A reservation already exists for this transaction.");
            problem.setTitle("Duplicate Reservation");
            problem.setType(URI.create("https://nexus.com/errors/duplicate-reservation"));
            problem.setProperty("errorCode", "DUPLICATE_RESERVATION");
            problem.setProperty("timestamp", Instant.now().toString());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "A data integrity constraint was violated.");
        problem.setTitle("Data Integrity Violation");
        problem.setType(URI.create("https://nexus.com/errors/data-integrity"));
        problem.setProperty("errorCode", "DATA_INTEGRITY_VIOLATION");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    // ══════════════════════════════════════════════════════════
    // AUTH — 401 / 403
    // ══════════════════════════════════════════════════════════

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ProblemDetail> handleUnauthorized(
            UnauthorizedException ex) {
        log.warn("Unauthorized: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Unauthorized");
        problem.setType(URI.create("https://nexus.com/errors/unauthorized"));
        problem.setProperty("errorCode", "UNAUTHORIZED");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Access Denied");
        problem.setType(URI.create("https://nexus.com/errors/access-denied"));
        problem.setProperty("errorCode", "ACCESS_DENIED");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    // ══════════════════════════════════════════════════════════
    // VALIDATION & GENERAL EXCEPTIONS
    // ══════════════════════════════════════════════════════════

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed.");
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://nexus.com/errors/validation"));
        problem.setProperty("errorCode", "VALIDATION_FAILED");
        problem.setProperty("timestamp", Instant.now().toString());

        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> java.util.Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() != null
                                ? fe.getDefaultMessage() : "invalid",
                        "rejectedValue", String.valueOf(fe.getRejectedValue())))
                .toList();
        problem.setProperty("fieldErrors", fieldErrors);

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(
            IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request");
        problem.setType(URI.create("https://nexus.com/errors/invalid-request"));
        problem.setProperty("errorCode", "INVALID_ARGUMENT");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> handleIllegalState(
            IllegalStateException ex) {
        log.warn("Invalid state: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Invalid Account State");
        problem.setType(URI.create("https://nexus.com/errors/invalid-state"));
        problem.setProperty("errorCode", "INVALID_STATE");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex, HttpServletResponse response) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);

        // Response already committed to a non-JSON content type (e.g. an
        // /actuator/prometheus scrape aborted mid-write) — writing a
        // ProblemDetail here would only raise a secondary conversion error.
        if (response.isCommitted()) {
            return null;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://nexus.com/errors/internal"));
        problem.setProperty("errorCode", "INTERNAL_ERROR");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem);
    }
}