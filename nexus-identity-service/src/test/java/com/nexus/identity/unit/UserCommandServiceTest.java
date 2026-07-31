package com.nexus.identity.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.identity.application.command.*;
import com.nexus.identity.domain.model.*;
import com.nexus.identity.domain.model.enums.UserStatus;
import com.nexus.identity.infrastructure.ai.KycRejectionExplainer;
import com.nexus.identity.infrastructure.aws.CognitoUserMirror;
import com.nexus.identity.infrastructure.aws.S3DocumentUploader;
import com.nexus.identity.infrastructure.aws.SqsKycPublisher;
import com.nexus.identity.infrastructure.jwt.JwtIssuer;
import com.nexus.identity.infrastructure.persistence.*;
import com.nexus.identity.infrastructure.redis.JwtBlacklistRepository;
import com.nexus.identity.infrastructure.redis.SessionCacheRepository;
import com.nexus.identity.web.dto.request.*;
import com.nexus.identity.web.dto.response.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserCommandService — the CQRS write side of identity.
 * All collaborators are mocked; ObjectMapper and MeterRegistry are real
 * instances since mocking Jackson node-building / Micrometer counters
 * adds no value and breaks internal null-safety assumptions.
 */
@ExtendWith(MockitoExtension.class)
class UserCommandServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private KycVerificationRepository kycRepository;
    @Mock private AuditLogRepository auditRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private PasswordHistoryRepository passwordHistoryRepository;
    @Mock private JwtIssuer jwtIssuer;
    @Mock private JwtBlacklistRepository blacklistRepository;
    @Mock private SessionCacheRepository sessionCacheRepository;
    @Mock private S3DocumentUploader s3Uploader;
    @Mock private SqsKycPublisher sqsPublisher;
    @Mock private CognitoUserMirror cognitoMirror;
    @Mock private KycRejectionExplainer rejectionExplainer;
    @Mock private BCryptPasswordEncoder passwordEncoder;
    @Mock private Tracer tracer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

    private UserCommandService service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "user@example.com";

    @BeforeEach
    void setUp() {
        service = new UserCommandService(
                userRepository, sessionRepository, kycRepository, auditRepository,
                outboxRepository, passwordHistoryRepository, jwtIssuer, blacklistRepository,
                sessionCacheRepository, s3Uploader, sqsPublisher, cognitoMirror,
                rejectionExplainer, passwordEncoder, objectMapper, observationRegistry,
                tracer, meterRegistry);

        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(outboxRepository.save(any(OutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(auditRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private RegisterRequest registerRequest() {
        return new RegisterRequest(EMAIL, "Sup3rSecret!Pass", "Jane Doe",
                "+5215512345678", LocalDate.of(1990, 1, 1), "MX");
    }

    private User activeUser() {
        return User.builder()
                .userId(USER_ID)
                .email(EMAIL)
                .phoneNumber("+5215512345678")
                .passwordHash("$2a$hash")
                .fullName("Jane Doe")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .country("MX")
                .status(UserStatus.ACTIVE)
                .roles(List.of("USER"))
                .failedLoginAttempts(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ══════════════════════════════════════════════════════════
    // REGISTER
    // ══════════════════════════════════════════════════════════
    @Nested
    class Register {

        @Test
        void succeedsAndReturnsPendingKycUser() {
            when(userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(false);
            when(userRepository.existsByPhoneNumberAndDeletedAtIsNull(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("hashed-pw");

            RegisterResponse response = service.register(registerRequest(), "1.2.3.4", "ua", "trace-1");

            assertThat(response.userId()).isNotBlank();
            assertThat(UUID.fromString(response.userId())).isNotNull();
            assertThat(response.message()).contains("Registration successful");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.PENDING_KYC);
            assertThat(userCaptor.getValue().getEmail()).isEqualTo(EMAIL);

            verify(cognitoMirror).mirrorNewUser(any(), eq(EMAIL), eq("Sup3rSecret!Pass"));
            verify(passwordHistoryRepository).save(any(PasswordHistory.class));
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("UserRegistered")));
            verify(auditRepository).save(argThat(a -> a.getEventType().equals("USER_REGISTERED")));
            assertThat(meterRegistry.counter("identity.registrations", "outcome", "success").count()).isEqualTo(1.0);
        }

        @Test
        void lowercasesEmailBeforeStoring() {
            RegisterRequest req = new RegisterRequest("UPPER@Example.com", "Sup3rSecret!Pass",
                    "Jane Doe", "+5215512345678", LocalDate.of(1990, 1, 1), "MX");
            when(userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(anyString())).thenReturn(false);
            when(userRepository.existsByPhoneNumberAndDeletedAtIsNull(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("hashed-pw");

            service.register(req, "1.2.3.4", "ua", "trace-1");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getEmail()).isEqualTo("upper@example.com");
        }

        @Test
        void rejectsDuplicateEmail() {
            when(userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> service.register(registerRequest(), "1.2.3.4", "ua", "trace-1"))
                    .isInstanceOf(DuplicateEmailException.class);

            verify(userRepository, never()).save(any());
            assertThat(meterRegistry.counter("identity.registrations", "outcome", "failed").count()).isEqualTo(1.0);
        }

        @Test
        void rejectsDuplicatePhone() {
            when(userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(false);
            when(userRepository.existsByPhoneNumberAndDeletedAtIsNull(anyString())).thenReturn(true);

            assertThatThrownBy(() -> service.register(registerRequest(), "1.2.3.4", "ua", "trace-1"))
                    .isInstanceOf(DuplicatePhoneException.class);

            verify(userRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════
    // LOGIN
    // ══════════════════════════════════════════════════════════
    @Nested
    class Login {

        private LoginRequest loginRequest() {
            return new LoginRequest(EMAIL, "correct-password", "device-abc");
        }

        @Test
        void succeedsAndIssuesTokens() {
            User user = activeUser();
            when(sessionCacheRepository.getFailedAttempts(EMAIL)).thenReturn(0);
            when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(eq("correct-password"), anyString())).thenReturn(true);
            when(passwordEncoder.encode(anyString())).thenReturn("refresh-hash");

            JwtIssuer.TokenPair tokenPair = new JwtIssuer.TokenPair(
                    "access-token", "refresh-token", UUID.randomUUID().toString(),
                    Instant.now(), Instant.now().plusSeconds(900));
            when(jwtIssuer.issueTokens(eq(user), eq("device-abc"))).thenReturn(tokenPair);
            when(cognitoMirror.loginWithCognito(anyString(), anyString())).thenReturn(Optional.empty());

            LoginResponse response = service.login(loginRequest(), "1.2.3.4", "ua", "trace-1");

            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(response.refreshToken()).isEqualTo("refresh-token");
            assertThat(response.userId()).isEqualTo(USER_ID.toString());
            assertThat(response.tokenType()).isEqualTo("Bearer");

            verify(sessionRepository).save(any(Session.class));
            verify(sessionCacheRepository).resetFailedAttempts(EMAIL);
            verify(sessionCacheRepository).invalidate(USER_ID);
            assertThat(meterRegistry.counter("identity.logins", "outcome", "success").count()).isEqualTo(1.0);
        }

        @Test
        void rateLimitsAfterFiveFailedAttempts() {
            when(sessionCacheRepository.getFailedAttempts(EMAIL)).thenReturn(5);

            assertThatThrownBy(() -> service.login(loginRequest(), "1.2.3.4", "ua", "trace-1"))
                    .isInstanceOf(AccountLockedException.class);

            verify(userRepository, never()).findByEmailIgnoreCaseAndDeletedAtIsNull(any());
        }

        @Test
        void rejectsUnknownEmailWithGenericMessage() {
            when(sessionCacheRepository.getFailedAttempts(EMAIL)).thenReturn(0);
            when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.empty());
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> service.login(loginRequest(), "1.2.3.4", "ua", "trace-1"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessage("Invalid credentials");

            verify(sessionCacheRepository).incrementFailedAttempts(EMAIL);
        }

        @Test
        void rejectsWrongPasswordAndIncrementsFailedAttempts() {
            User user = activeUser();
            when(sessionCacheRepository.getFailedAttempts(EMAIL)).thenReturn(0);
            when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> service.login(loginRequest(), "1.2.3.4", "ua", "trace-1"))
                    .isInstanceOf(InvalidCredentialsException.class);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        void locksAccountOnFifthFailedAttempt() {
            User user = activeUser();
            user.setFailedLoginAttempts(4);
            when(sessionCacheRepository.getFailedAttempts(EMAIL)).thenReturn(0);
            when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> service.login(loginRequest(), "1.2.3.4", "ua", "trace-1"))
                    .isInstanceOf(InvalidCredentialsException.class);

            assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
            assertThat(user.isAccountLocked()).isTrue();
        }

        @Test
        void rejectsLockedAccount() {
            User user = activeUser();
            user.setLockUntil(Instant.now().plusSeconds(600));
            when(sessionCacheRepository.getFailedAttempts(EMAIL)).thenReturn(0);
            when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

            assertThatThrownBy(() -> service.login(loginRequest(), "1.2.3.4", "ua", "trace-1"))
                    .isInstanceOf(AccountLockedException.class);
        }

        @Test
        void rejectsSuspendedAccount() {
            User user = activeUser();
            user.setStatus(UserStatus.SUSPENDED);
            when(sessionCacheRepository.getFailedAttempts(EMAIL)).thenReturn(0);
            when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

            assertThatThrownBy(() -> service.login(loginRequest(), "1.2.3.4", "ua", "trace-1"))
                    .isInstanceOf(AccountSuspendedException.class);
        }
    }

    // ══════════════════════════════════════════════════════════
    // LOGOUT
    // ══════════════════════════════════════════════════════════
    @Nested
    class Logout {

        @Test
        void deactivatesSessionAndBlacklistsToken() {
            UUID jti = UUID.randomUUID();
            Session session = Session.builder().sessionId(UUID.randomUUID()).userId(USER_ID)
                    .jti(jti).isActive(true).build();
            when(sessionRepository.findByJtiAndIsActiveTrue(jti)).thenReturn(Optional.of(session));
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(activeUser()));

            Instant expiresAt = Instant.now().plusSeconds(900);
            service.logout(USER_ID, jti.toString(), expiresAt, "1.2.3.4", "trace-1");

            assertThat(session.isActive()).isFalse();
            verify(sessionRepository).save(session);
            verify(blacklistRepository).blacklist(jti.toString(), expiresAt);
            verify(blacklistRepository).publishRevocationEvent(jti.toString());
            verify(sessionCacheRepository).invalidate(USER_ID);
            verify(cognitoMirror).revokeSession(EMAIL);
        }

        @Test
        void isNoOpWhenSessionAlreadyInactive() {
            UUID jti = UUID.randomUUID();
            when(sessionRepository.findByJtiAndIsActiveTrue(jti)).thenReturn(Optional.empty());
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

            service.logout(USER_ID, jti.toString(), Instant.now(), "1.2.3.4", "trace-1");

            verify(sessionRepository, never()).save(any());
            verify(blacklistRepository).blacklist(anyString(), any());
        }
    }

    // ══════════════════════════════════════════════════════════
    // CHANGE PASSWORD
    // ══════════════════════════════════════════════════════════
    @Nested
    class ChangePassword {

        private ChangePasswordRequest request() {
            return new ChangePasswordRequest("current-pw", "NewSup3rSecret!Pass");
        }

        @Test
        void succeedsAndRevokesOtherSessions() {
            User user = activeUser();
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(eq("current-pw"), anyString())).thenReturn(true);
            when(passwordHistoryRepository.findTop5ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
            when(passwordEncoder.encode(anyString())).thenReturn("new-hash");

            UUID currentSession = UUID.randomUUID();
            Session other = Session.builder().sessionId(UUID.randomUUID()).userId(USER_ID)
                    .jti(UUID.randomUUID()).isActive(true).expiresAt(Instant.now().plusSeconds(900)).build();
            Session current = Session.builder().sessionId(currentSession).userId(USER_ID)
                    .jti(UUID.randomUUID()).isActive(true).expiresAt(Instant.now().plusSeconds(900)).build();
            when(sessionRepository.findActiveSessionsForUser(USER_ID)).thenReturn(List.of(other, current));

            service.changePassword(USER_ID, request(), currentSession, "1.2.3.4", "trace-1");

            assertThat(other.isActive()).isFalse();
            assertThat(current.isActive()).isTrue();
            verify(blacklistRepository).blacklist(eq(other.getJti().toString()), any());
            verify(blacklistRepository, never()).blacklist(eq(current.getJti().toString()), any());
            verify(passwordHistoryRepository).save(any(PasswordHistory.class));
        }

        @Test
        void rejectsWrongCurrentPassword() {
            User user = activeUser();
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> service.changePassword(USER_ID, request(), UUID.randomUUID(), "1.2.3.4", "trace-1"))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(auditRepository).save(argThat(a -> a.getEventType().equals("PASSWORD_CHANGE_FAILED")));
        }

        @Test
        void rejectsReusedPassword() {
            User user = activeUser();
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(eq("current-pw"), anyString())).thenReturn(true);
            PasswordHistory history = PasswordHistory.builder().userId(USER_ID).passwordHash("old-hash").build();
            when(passwordHistoryRepository.findTop5ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(history));
            when(passwordEncoder.matches(eq("NewSup3rSecret!Pass"), eq("old-hash"))).thenReturn(true);

            assertThatThrownBy(() -> service.changePassword(USER_ID, request(), UUID.randomUUID(), "1.2.3.4", "trace-1"))
                    .isInstanceOf(PasswordReusedException.class);
        }

        @Test
        void throwsWhenUserNotFound() {
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changePassword(USER_ID, request(), UUID.randomUUID(), "1.2.3.4", "trace-1"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // ══════════════════════════════════════════════════════════
    // PASSWORD RESET
    // ══════════════════════════════════════════════════════════
    @Nested
    class PasswordReset {

        @Test
        void requestSilentlyIgnoresUnknownEmail() {
            when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.empty());

            service.requestPasswordReset(EMAIL);

            verify(sessionCacheRepository, never()).storePasswordResetToken(any(), any(), any());
            verify(outboxRepository, never()).save(any());
        }

        @Test
        void requestStoresTokenAndWritesOutboxForKnownEmail() {
            when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(activeUser()));

            service.requestPasswordReset(EMAIL);

            verify(sessionCacheRepository).storePasswordResetToken(anyString(), eq(USER_ID), any());
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("PasswordResetRequested")));
        }

        @Test
        void confirmRejectsInvalidToken() {
            when(sessionCacheRepository.resolvePasswordResetToken("bad-token")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmPasswordReset("bad-token", "NewSup3rSecret!Pass"))
                    .isInstanceOf(InvalidPasswordResetTokenException.class);
        }

        @Test
        void confirmThrowsWhenUserMissing() {
            when(sessionCacheRepository.resolvePasswordResetToken("tok")).thenReturn(Optional.of(USER_ID));
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmPasswordReset("tok", "NewSup3rSecret!Pass"))
                    .isInstanceOf(UserNotFoundException.class);
        }

        @Test
        void confirmRejectsReusedPassword() {
            when(sessionCacheRepository.resolvePasswordResetToken("tok")).thenReturn(Optional.of(USER_ID));
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(activeUser()));
            PasswordHistory history = PasswordHistory.builder().userId(USER_ID).passwordHash("old-hash").build();
            when(passwordHistoryRepository.findTop5ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(history));
            when(passwordEncoder.matches(anyString(), eq("old-hash"))).thenReturn(true);

            assertThatThrownBy(() -> service.confirmPasswordReset("tok", "NewSup3rSecret!Pass"))
                    .isInstanceOf(PasswordReusedException.class);
        }

        @Test
        void confirmSucceedsAndRevokesAllSessionsAndBurnsToken() {
            when(sessionCacheRepository.resolvePasswordResetToken("tok")).thenReturn(Optional.of(USER_ID));
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(activeUser()));
            when(passwordHistoryRepository.findTop5ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
            when(passwordEncoder.encode(anyString())).thenReturn("new-hash");

            Session s1 = Session.builder().sessionId(UUID.randomUUID()).userId(USER_ID)
                    .jti(UUID.randomUUID()).isActive(true).expiresAt(Instant.now().plusSeconds(900)).build();
            when(sessionRepository.findActiveSessionsForUser(USER_ID)).thenReturn(List.of(s1));

            service.confirmPasswordReset("tok", "NewSup3rSecret!Pass");

            assertThat(s1.isActive()).isFalse();
            verify(sessionCacheRepository).deletePasswordResetToken("tok");
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("PasswordResetCompleted")));
        }
    }

    // ══════════════════════════════════════════════════════════
    // SESSION TERMINATION
    // ══════════════════════════════════════════════════════════
    @Nested
    class TerminateSession {

        @Test
        void deactivatesOwnedSession() {
            UUID sessionId = UUID.randomUUID();
            Session session = Session.builder().sessionId(sessionId).userId(USER_ID)
                    .jti(UUID.randomUUID()).isActive(true).expiresAt(Instant.now().plusSeconds(900)).build();
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            service.terminateSession(USER_ID, sessionId);

            assertThat(session.isActive()).isFalse();
            verify(blacklistRepository).blacklist(eq(session.getJti().toString()), any());
        }

        @Test
        void rejectsTerminatingAnotherUsersSession() {
            UUID sessionId = UUID.randomUUID();
            Session session = Session.builder().sessionId(sessionId).userId(UUID.randomUUID())
                    .jti(UUID.randomUUID()).isActive(true).build();
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.terminateSession(USER_ID, sessionId))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        void isNoOpWhenSessionNotFound() {
            UUID sessionId = UUID.randomUUID();
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            assertDoesNotThrow(() -> service.terminateSession(USER_ID, sessionId));
            verify(blacklistRepository, never()).blacklist(any(), any());
        }
    }

    // ══════════════════════════════════════════════════════════
    // SAGA COMPENSATION — cancelRegistration
    // ══════════════════════════════════════════════════════════
    @Nested
    class CancelRegistration {

        @Test
        void cancelsPendingRegistration() {
            User user = activeUser();
            user.setStatus(UserStatus.PENDING_KYC);
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
            when(sessionRepository.findActiveSessionsForUser(USER_ID)).thenReturn(List.of());

            service.cancelRegistration(USER_ID, "saga-1", "trace-1");

            assertThat(user.getStatus()).isEqualTo(UserStatus.REGISTRATION_CANCELLED);
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("UserRegistrationCancelled")));
        }

        @Test
        void isIdempotentWhenAlreadyCancelled() {
            User user = activeUser();
            user.setStatus(UserStatus.REGISTRATION_CANCELLED);
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));

            service.cancelRegistration(USER_ID, "saga-1", "trace-1");

            verify(userRepository, never()).save(any());
            verify(outboxRepository, never()).save(any());
        }

        @Test
        void isNoOpWhenUserNotFound() {
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

            assertDoesNotThrow(() -> service.cancelRegistration(USER_ID, "saga-1", "trace-1"));
            verify(outboxRepository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════
    // KYC INITIATION (Structured Concurrency)
    // ══════════════════════════════════════════════════════════
    @Nested
    class InitiateKyc {

        private final MockMultipartFile document =
                new MockMultipartFile("document", "id.jpg", "image/jpeg", new byte[]{1, 2, 3});

        @Test
        void succeedsAndPublishesForAnalysis() throws Exception {
            when(sessionCacheRepository.getKycRetryCount(USER_ID)).thenReturn(0);
            when(s3Uploader.uploadKycDocument(eq(USER_ID), any(), eq("PASSPORT"), any()))
                    .thenReturn("kyc/path.jpg");
            when(kycRepository.countAttemptsByUserId(USER_ID)).thenReturn(0);
            when(kycRepository.save(any(KycVerification.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(activeUser()));

            KycInitiationResponse response = service.initiateKyc(USER_ID, document, "PASSPORT",
                    "Jane Doe", "1990-01-01", "X1234", "MX", "es", "1.2.3.4", "trace-1");

            assertThat(response.verificationId()).isNotBlank();
            verify(sqsPublisher).publishKycDocumentForAnalysis(eq(USER_ID), any(), eq("kyc/path.jpg"), eq("PASSPORT"));
            verify(sessionCacheRepository).incrementKycRetryCount(USER_ID);
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("KycInitiated")));
        }

        @Test
        void rejectsWhenRetryLimitReached() {
            when(sessionCacheRepository.getKycRetryCount(USER_ID)).thenReturn(3);

            Exception ex = assertThrows(Exception.class, () -> service.initiateKyc(USER_ID, document, "PASSPORT",
                    "Jane Doe", "1990-01-01", "X1234", "MX", "es", "1.2.3.4", "trace-1"));

            assertThat(rootCause(ex)).isInstanceOf(KycRetryLimitExceededException.class);
            verify(kycRepository, never()).save(any());
        }

        private Throwable rootCause(Throwable t) {
            Throwable current = t;
            while (current.getCause() != null && current.getCause() != current) {
                current = current.getCause();
            }
            return current;
        }
    }

    // ══════════════════════════════════════════════════════════
    // KYC RESULT PROCESSING
    // ══════════════════════════════════════════════════════════
    @Nested
    class ProcessKycResult {

        private final UUID verificationId = UUID.randomUUID();

        private KycVerification pendingVerification() {
            return KycVerification.builder()
                    .verificationId(verificationId)
                    .userId(USER_ID)
                    .documentType("PASSPORT")
                    .build();
        }

        @Test
        void approvesUserOnApprovedResult() {
            when(kycRepository.findByVerificationId(verificationId)).thenReturn(Optional.of(pendingVerification()));
            User user = activeUser();
            user.setStatus(UserStatus.KYC_IN_PROGRESS);
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
            when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            KycResultRequest result = new KycResultRequest(verificationId.toString(), true,
                    java.util.Map.of(), java.util.Map.of(), List.of());

            service.processKycResult(USER_ID, verificationId, result, "trace-1");

            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.isKycVerified()).isTrue();
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("IdentityVerified")));
            verify(cognitoMirror).syncStatus(eq(USER_ID), eq(EMAIL), eq("ACTIVE"), eq(true));
        }

        @Test
        void rejectsUserAndAllowsRetryUnderThreeAttempts() {
            when(kycRepository.findByVerificationId(verificationId)).thenReturn(Optional.of(pendingVerification()));
            User user = activeUser();
            user.setStatus(UserStatus.KYC_IN_PROGRESS);
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
            when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(kycRepository.countAttemptsByUserId(USER_ID)).thenReturn(1);
            when(rejectionExplainer.explain(anyList(), eq("es"))).thenReturn("Intenta de nuevo");

            KycResultRequest result = new KycResultRequest(verificationId.toString(), false,
                    java.util.Map.of(), java.util.Map.of(), List.of("FACE_BLURRY"));

            service.processKycResult(USER_ID, verificationId, result, "trace-1");

            assertThat(user.getStatus()).isEqualTo(UserStatus.KYC_REJECTED);
            verify(outboxRepository).save(argThat(e -> e.getEventType().equals("IdentityRejected")));
        }

        @Test
        void permanentlyRejectsUserOnThirdFailedAttempt() {
            when(kycRepository.findByVerificationId(verificationId)).thenReturn(Optional.of(pendingVerification()));
            User user = activeUser();
            user.setStatus(UserStatus.KYC_IN_PROGRESS);
            when(userRepository.findByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
            when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(kycRepository.countAttemptsByUserId(USER_ID)).thenReturn(3);
            when(rejectionExplainer.explain(anyList(), eq("es"))).thenReturn("No mas intentos");

            KycResultRequest result = new KycResultRequest(verificationId.toString(), false,
                    java.util.Map.of(), java.util.Map.of(), List.of("DOCUMENT_EXPIRED"));

            service.processKycResult(USER_ID, verificationId, result, "trace-1");

            assertThat(user.getStatus()).isEqualTo(UserStatus.KYC_REJECTED_PERMANENT);
        }

        @Test
        void throwsWhenVerificationNotFound() {
            when(kycRepository.findByVerificationId(verificationId)).thenReturn(Optional.empty());

            KycResultRequest result = new KycResultRequest(verificationId.toString(), true,
                    java.util.Map.of(), java.util.Map.of(), List.of());

            assertThatThrownBy(() -> service.processKycResult(USER_ID, verificationId, result, "trace-1"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Verification not found");
        }
    }
}
