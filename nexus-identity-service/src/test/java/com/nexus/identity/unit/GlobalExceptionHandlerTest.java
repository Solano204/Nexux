package com.nexus.identity.unit;

import com.nexus.identity.application.command.*;
import com.nexus.identity.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ServletWebRequest webRequest = new ServletWebRequest(new MockHttpServletRequest());

    @Test
    void duplicateEmailReturns409WithProblemDetail() {
        ResponseEntity<ProblemDetail> response = handler.handleDuplicateEmail(
                new DuplicateEmailException("Email exists"), webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties().get("error")).isEqualTo("EMAIL_EXISTS");
    }

    @Test
    void duplicatePhoneReturns409() {
        ResponseEntity<ProblemDetail> response = handler.handleDuplicatePhone(
                new DuplicatePhoneException("Phone exists"), webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getDetail()).isEqualTo("Phone number already registered");
    }

    @Test
    void passwordReusedReturns422() {
        ResponseEntity<ProblemDetail> response = handler.handlePasswordReused(
                new PasswordReusedException("Reused"), webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void invalidCredentialsReturnsGenericVagueMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleInvalidCredentials(
                new InvalidCredentialsException("some internal detail that should not leak"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("message")).isEqualTo("Invalid credentials");
    }

    @Test
    void accountSuspendedReturns403() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleAccountSuspended(new AccountSuspendedException("suspended"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error")).isEqualTo("ACCOUNT_SUSPENDED");
    }

    @Test
    void accountLockedReturns403WithOriginalMessage() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleAccountLocked(new AccountLockedException("Locked until 10:00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("message")).isEqualTo("Locked until 10:00");
    }

    @Test
    void kycRetryLimitReturns403() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleKycRetryLimit(new KycRetryLimitExceededException("limit reached"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error")).isEqualTo("KYC_RETRY_LIMIT_EXCEEDED");
    }

    @Test
    void unauthorizedReturns401() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUnauthorized(new UnauthorizedException("no header"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void userNotFoundReturns404WithGenericMessage() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUserNotFound(new UserNotFoundException("internal id leaked in message"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("message")).isEqualTo("User not found");
    }

    @Test
    void validationErrorsAggregateFieldMessages() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must be a valid email"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                (org.springframework.core.MethodParameter) null, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, String> fieldErrors = (Map<String, String>) response.getBody().get("fieldErrors");
        assertThat(fieldErrors).containsEntry("email", "must be a valid email");
    }

    @Test
    void invalidResetTokenReturns400() {
        ResponseEntity<Map<String, Object>> response = handler.handleInvalidResetToken(
                new InvalidPasswordResetTokenException("expired"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("INVALID_RESET_TOKEN");
    }

    @Test
    void documentUploadFailureReturns502() {
        ResponseEntity<Map<String, Object>> response = handler.handleDocumentUpload(
                new DocumentUploadException("s3 down", new RuntimeException()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void unhandledExceptionReturns500WithGenericMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneral(
                new RuntimeException("stack trace details that should not leak"), webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
    }
}
