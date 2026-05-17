package com.nexus.identity.web.advice;

import com.nexus.identity.application.command.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global Exception Handler — converts domain exceptions to RFC 7807 responses.
 *
 * Security principle: error messages are deliberately vague for
 * authentication failures (prevents user enumeration attacks).
 * Other validation errors are specific to aid the developer/user.
 *
 * All handlers return ProblemDetail (Spring 6 RFC 7807 support):
 * {
 *   "type": "https://nexusbank.com/errors/EMAIL_EXISTS",
 *   "title": "Conflict",
 *   "status": 409,
 *   "detail": "...",
 *   "instance": "/api/v1/auth/register",
 *   "timestamp": "2025-..."
 * }
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 409 Conflict ─────────────────────────────────────────

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateEmail(
            DuplicateEmailException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create(
                "https://nexusbank.com/errors/EMAIL_EXISTS"));
        problem.setTitle("Email Already Registered");
        problem.setProperty("error", "EMAIL_EXISTS");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DuplicatePhoneException.class)
    public ResponseEntity<ProblemDetail> handleDuplicatePhone(
            DuplicatePhoneException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Phone number already registered");
        problem.setType(URI.create(
                "https://nexusbank.com/errors/PHONE_EXISTS"));
        problem.setProperty("error", "PHONE_EXISTS");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(PasswordReusedException.class)
    public ResponseEntity<ProblemDetail> handlePasswordReused(
            PasswordReusedException ex, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create(
                "https://nexusbank.com/errors/PASSWORD_REUSE"));
        problem.setProperty("error", "PASSWORD_REUSE");
        problem.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(problem);
    }

    // ── 401 Unauthorized ─────────────────────────────────────

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(
            InvalidCredentialsException ex) {
        // Deliberately vague: same response for wrong password AND unknown email
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "error", "INVALID_CREDENTIALS",
                        "message", "Invalid credentials",
                        "timestamp", Instant.now().toString()
                ));
    }

    // ── 403 Forbidden ─────────────────────────────────────────

    @ExceptionHandler(AccountSuspendedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountSuspended(
            AccountSuspendedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "error", "ACCOUNT_SUSPENDED",
                        "message", "Account suspended. Contact support.",
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountLocked(
            AccountLockedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "error", "ACCOUNT_LOCKED",
                        "message", ex.getMessage(),
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(KycRetryLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleKycRetryLimit(
            KycRetryLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "error", "KYC_RETRY_LIMIT_EXCEEDED",
                        "message", ex.getMessage(),
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(
            UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "error", "UNAUTHORIZED",
                        "message", ex.getMessage(),
                        "timestamp", Instant.now().toString()
                ));
    }

    // ── 404 Not Found ─────────────────────────────────────────

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(
            UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "USER_NOT_FOUND",
                        "message", "User not found",
                        "timestamp", Instant.now().toString()
                ));
    }

    // ── 400 Bad Request — validation ─────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        f -> f.getDefaultMessage() != null
                                ? f.getDefaultMessage() : "invalid",
                        (a, b) -> a
                ));

        return ResponseEntity.badRequest()
                .body(Map.of(
                        "error", "VALIDATION_FAILED",
                        "message", "Request validation failed",
                        "fieldErrors", fieldErrors,
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidResetToken(
            InvalidPasswordResetTokenException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "INVALID_RESET_TOKEN",
                        "message", "Password reset token is invalid or has expired",
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(DocumentUploadException.class)
    public ResponseEntity<Map<String, Object>> handleDocumentUpload(
            DocumentUploadException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "error", "DOCUMENT_UPLOAD_FAILED",
                        "message", "Document upload failed. Please try again.",
                        "timestamp", Instant.now().toString()
                ));
    }

    // ── 500 Internal Server Error ─────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(
            Exception ex, WebRequest request) {

        log.error("Unhandled exception: path={} error={}",
                request.getDescription(false), ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", "INTERNAL_ERROR",
                        "message", "An unexpected error occurred",
                        "timestamp", Instant.now().toString()
                ));
    }
}