package com.nexus.identity.unit;

import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.domain.model.User;
import com.nexus.identity.domain.model.enums.UserStatus;
import com.nexus.identity.infrastructure.ai.KycRejectionExplainer;
import com.nexus.identity.infrastructure.aws.S3DocumentUploader;
import com.nexus.identity.infrastructure.aws.SqsKycPublisher;
import com.nexus.identity.infrastructure.jwt.JwtIssuer;
import com.nexus.identity.infrastructure.persistence.*;
import com.nexus.identity.infrastructure.redis.JwtBlacklistRepository;
import com.nexus.identity.infrastructure.redis.SessionCacheRepository;
import com.nexus.identity.web.dto.request.RegisterRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class UserCommandServiceTest {

    @Mock UserRepository userRepository;
    @Mock SessionRepository sessionRepository;
    @Mock KycVerificationRepository kycRepository;
    @Mock AuditLogRepository auditRepository;
    @Mock OutboxRepository outboxRepository;
    @Mock PasswordHistoryRepository passwordHistoryRepository;
    @Mock JwtIssuer jwtIssuer;
    @Mock JwtBlacklistRepository blacklistRepository;
    @Mock SessionCacheRepository sessionCacheRepository;
    @Mock S3DocumentUploader s3Uploader;
    @Mock SqsKycPublisher sqsPublisher;
    @Mock KycRejectionExplainer rejectionExplainer;

    UserCommandService commandService;

    @BeforeEach
    void setUp() {
        commandService = new UserCommandService(
                userRepository, sessionRepository, kycRepository,
                auditRepository, outboxRepository, passwordHistoryRepository,
                jwtIssuer, blacklistRepository, sessionCacheRepository,
                s3Uploader, sqsPublisher, rejectionExplainer,
                new BCryptPasswordEncoder(4), // Low cost for tests
                new com.fasterxml.jackson.databind.ObjectMapper(),
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("register: success creates user, outbox entry, and audit log")
    void register_validRequest_createsUserAndOutboxEntry() {
        RegisterRequest request = new RegisterRequest(
                "test@example.com",
                "SecurePassword123!",
                "Test User",
                "+525512345678",
                LocalDate.of(1990, 1, 1),
                "MX"
        );

        when(userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(anyString()))
                .thenReturn(false);
        when(userRepository.existsByPhoneNumberAndDeletedAtIsNull(anyString()))
                .thenReturn(false);
        when(userRepository.save(any(User.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(passwordHistoryRepository.save(any()))
                .thenReturn(null);
        when(outboxRepository.save(any()))
                .thenReturn(null);
        when(auditRepository.save(any()))
                .thenReturn(null);

        var response = commandService.register(
                request, "127.0.0.1", "Mozilla/5.0", "trace-001");

        assertThat(response).isNotNull();
        assertThat(response.userId()).isNotNull();
        assertThat(response.message())
                .contains("Registration successful");

        // Verify outbox was written (Debezium will publish UserRegistered)
        verify(outboxRepository).save(argThat(entry ->
                "UserRegistered".equals(entry.getEventType()) &&
                        "USER".equals(entry.getAggregateType())
        ));

        // Verify audit log was written
        verify(auditRepository).save(argThat(log ->
                "USER_REGISTERED".equals(log.getEventType())
        ));
    }

    @Test
    @DisplayName("register: duplicate email throws DuplicateEmailException")
    void register_duplicateEmail_throwsException() {
        RegisterRequest request = new RegisterRequest(
                "existing@example.com",
                "SecurePassword123!",
                "Test User",
                "+525512345678",
                LocalDate.of(1990, 1, 1),
                "MX"
        );

        when(userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(anyString()))
                .thenReturn(true);

        assertThatThrownBy(() ->
                commandService.register(
                        request, "127.0.0.1", "Mozilla", "trace-002")
        ).isInstanceOf(DuplicateEmailException.class);

        // Verify no DB writes happened
        verifyNoInteractions(outboxRepository);
    }

    @Test
    @DisplayName("login: wrong password increments failed attempts and throws")
    void login_wrongPassword_incrementsFailedAttempts() {
        var user = User.builder()
                .userId(UUID.randomUUID())
                .email("user@example.com")
                .passwordHash(new BCryptPasswordEncoder(4).encode("correct"))
                .status(UserStatus.ACTIVE)
                .failedLoginAttempts(0)
                .build();

        when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("user@example.com"))
                .thenReturn(Optional.of(user));
        when(sessionCacheRepository.getFailedAttempts("user@example.com"))
                .thenReturn(0);
        when(userRepository.save(any())).thenReturn(user);
        when(auditRepository.save(any())).thenReturn(null);

        var loginRequest = new com.nexus.identity.web.dto.request.LoginRequest(
                "user@example.com", "wrongpassword", "device-fp");

        assertThatThrownBy(() ->
                commandService.login(
                        loginRequest, "127.0.0.1", "Mozilla", "trace-003")
        ).isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid credentials"); // Deliberately vague

        // Failed attempts incremented
        verify(sessionCacheRepository).incrementFailedAttempts("user@example.com");

        // Audit log written
        verify(auditRepository).save(argThat(log ->
                "LOGIN_FAILED".equals(log.getEventType())
        ));
    }

    @Test
    @DisplayName("cancelRegistration: idempotent — already cancelled skips re-execution")
    void cancelRegistration_alreadyCancelled_isIdempotent() {
        User user = User.builder()
                .userId(UUID.randomUUID())
                .status(UserStatus.REGISTRATION_CANCELLED)
                .deletedAt(java.time.Instant.now())
                .build();

        when(userRepository.findByUserIdAndDeletedAtIsNull(user.getUserId()))
                .thenReturn(Optional.empty()); // Already soft-deleted

        commandService.cancelRegistration(
                user.getUserId(), "saga-001", "trace-004");

        // No writes should happen — already cancelled
        verifyNoInteractions(outboxRepository);
    }
}