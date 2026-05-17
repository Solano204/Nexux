package com.nexus.identity.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.identity.domain.model.*;
import com.nexus.identity.domain.model.enums.KycDecision;
import com.nexus.identity.domain.model.enums.UserStatus;
import com.nexus.identity.infrastructure.ai.KycRejectionExplainer;
import com.nexus.identity.infrastructure.aws.S3DocumentUploader;
import com.nexus.identity.infrastructure.aws.SqsKycPublisher;
import com.nexus.identity.infrastructure.jwt.JwtIssuer;
import com.nexus.identity.infrastructure.persistence.*;
import com.nexus.identity.infrastructure.redis.JwtBlacklistRepository;
import com.nexus.identity.infrastructure.redis.SessionCacheRepository;
import com.nexus.identity.web.dto.request.*;
import com.nexus.identity.web.dto.response.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;

/**
 * User Command Service — CQRS Write Side.
 *
 * Handles all state-changing operations:
 *   register, login, logout, changePassword,
 *   requestPasswordReset, confirmPasswordReset,
 *   initiateKyc, processKycResult,
 *   terminateSession, cancelRegistration (SAGA)
 *
 * All writes use @Transactional:
 *   domain write + outbox write = one atomic PostgreSQL transaction.
 *   Debezium reads the outbox via WAL → publishes to Kafka.
 *
 * Java 25 Structured Concurrency:
 *   StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow())
 *   replaces the Java 21 preview:
 *   new StructuredTaskScope.ShutdownOnFailure() + scope.join().throwIfFailed()
 *   In Java 25 join() itself throws FailedException if any subtask fails.
 */
@Slf4j
@Service
public class UserCommandService {

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final KycVerificationRepository kycRepository;
    private final AuditLogRepository auditRepository;
    private final OutboxRepository outboxRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;
    private final JwtIssuer jwtIssuer;
    private final JwtBlacklistRepository blacklistRepository;
    private final SessionCacheRepository sessionCacheRepository;
    private final S3DocumentUploader s3Uploader;
    private final SqsKycPublisher sqsPublisher;
    private final KycRejectionExplainer rejectionExplainer;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    /** Redis TTL for password reset tokens (30 minutes). */
    private static final Duration PASSWORD_RESET_TOKEN_TTL =
            Duration.ofMinutes(30);

    // ── Metrics ───────────────────────────────────────────────
    private final Counter registrationSuccessCounter;
    private final Counter registrationFailedCounter;
    private final Counter loginSuccessCounter;
    private final Counter loginFailedCounter;
    private final Counter kycInitiatedCounter;
    private final Counter passwordResetRequestCounter;
    private final Timer   bcryptTimer;

    public UserCommandService(
            UserRepository userRepository,
            SessionRepository sessionRepository,
            KycVerificationRepository kycRepository,
            AuditLogRepository auditRepository,
            OutboxRepository outboxRepository,
            PasswordHistoryRepository passwordHistoryRepository,
            JwtIssuer jwtIssuer,
            JwtBlacklistRepository blacklistRepository,
            SessionCacheRepository sessionCacheRepository,
            S3DocumentUploader s3Uploader,
            SqsKycPublisher sqsPublisher,
            KycRejectionExplainer rejectionExplainer,
            BCryptPasswordEncoder passwordEncoder,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.kycRepository = kycRepository;
        this.auditRepository = auditRepository;
        this.outboxRepository = outboxRepository;
        this.passwordHistoryRepository = passwordHistoryRepository;
        this.jwtIssuer = jwtIssuer;
        this.blacklistRepository = blacklistRepository;
        this.sessionCacheRepository = sessionCacheRepository;
        this.s3Uploader = s3Uploader;
        this.sqsPublisher = sqsPublisher;
        this.rejectionExplainer = rejectionExplainer;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        registrationSuccessCounter = Counter.builder("identity.registrations")
                .tag("outcome", "success").register(meterRegistry);
        registrationFailedCounter = Counter.builder("identity.registrations")
                .tag("outcome", "failed").register(meterRegistry);
        loginSuccessCounter = Counter.builder("identity.logins")
                .tag("outcome", "success").register(meterRegistry);
        loginFailedCounter = Counter.builder("identity.logins")
                .tag("outcome", "failed").register(meterRegistry);
        kycInitiatedCounter = Counter.builder("identity.kyc.initiations")
                .register(meterRegistry);
        passwordResetRequestCounter = Counter.builder(
                "identity.password.reset.requests").register(meterRegistry);
        bcryptTimer = Timer.builder("identity.bcrypt.duration")
                .description("BCrypt hash/verify duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
    }

    // ══════════════════════════════════════════════════════════
    // REGISTRATION
    // ══════════════════════════════════════════════════════════

    @Transactional
    public RegisterResponse register(RegisterRequest request,
                                     String ipAddress,
                                     String userAgent,
                                     String traceId) {

        Observation obs = Observation.createNotStarted(
                "identity.register", observationRegistry).start();

        try {
            if (userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(
                    request.email())) {
                registrationFailedCounter.increment();
                throw new DuplicateEmailException(
                        "Email already registered: " + maskEmail(request.email()));
            }

            if (userRepository.existsByPhoneNumberAndDeletedAtIsNull(
                    request.phoneNumber())) {
                registrationFailedCounter.increment();
                throw new DuplicatePhoneException(
                        "Phone number already registered");
            }

            String passwordHash = bcryptTimer.record(() ->
                    passwordEncoder.encode(request.password()));

            User user = User.builder()
                    .userId(UUID.randomUUID())
                    .email(request.email().toLowerCase())
                    .phoneNumber(request.phoneNumber())
                    .passwordHash(passwordHash)
                    .fullName(request.fullName())
                    .dateOfBirth(request.dateOfBirth())
                    .country(request.country())
                    .status(UserStatus.PENDING_KYC)
                    .roles(List.of("USER"))
                    .build();

            userRepository.save(user);
            savePasswordHistory(user.getUserId(), passwordHash);

            writeOutboxEntry("USER", user.getUserId(),
                    "UserRegistered", buildUserRegisteredPayload(user));

            writeAuditLog(user.getUserId(), "USER_REGISTERED",
                    ipAddress, userAgent, traceId,
                    Map.of(
                            "email", maskEmail(user.getEmail()),
                            "country", user.getCountry()
                    ));

            registrationSuccessCounter.increment();
            obs.event(Observation.Event.of("registration.success"));
            log.info("User registered: userId={} traceId={}",
                    user.getUserId(), traceId);

            return new RegisterResponse(
                    user.getUserId().toString(),
                    "Registration successful. " +
                            "Please complete KYC verification to activate your account."
            );

        } catch (DuplicateEmailException | DuplicatePhoneException e) {
            obs.event(Observation.Event.of("registration.duplicate"));
            throw e;
        } catch (Exception e) {
            obs.error(e);
            registrationFailedCounter.increment();
            throw e;
        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // LOGIN
    // ══════════════════════════════════════════════════════════

    @Transactional
    public LoginResponse login(LoginRequest request,
                               String ipAddress,
                               String userAgent,
                               String traceId) {

        Observation obs = Observation.createNotStarted(
                        "identity.login", observationRegistry)
                .lowCardinalityKeyValue("outcome", "pending")
                .start();

        try {
            String email = request.email().toLowerCase();

            int redisFailures =
                    sessionCacheRepository.getFailedAttempts(email);
            if (redisFailures >= 5) {
                loginFailedCounter.increment();
                obs.event(Observation.Event.of("login.rate_limited"));
                throw new AccountLockedException(
                        "Too many failed attempts. Try again later.");
            }

            Optional<User> userOpt = userRepository
                    .findByEmailIgnoreCaseAndDeletedAtIsNull(email);

            // Always BCrypt — prevents timing-based user enumeration
            String hashToCompare = userOpt
                    .map(User::getPasswordHash)
                    .orElse("$2a$12$dummy.hash.for.timing.safety.xxxxx");

            boolean passwordMatches = bcryptTimer.record(() ->
                    passwordEncoder.matches(request.password(), hashToCompare));

            if (userOpt.isEmpty() || !passwordMatches) {
                userOpt.ifPresent(u -> {
                    u.incrementFailedLoginAttempts();
                    if (u.getFailedLoginAttempts() >= 5) {
                        u.lockAccount(Instant.now()
                                .plus(Duration.ofMinutes(15)));
                    }
                    userRepository.save(u);
                });

                sessionCacheRepository.incrementFailedAttempts(email);

                writeAuditLog(
                        userOpt.map(User::getUserId).orElse(null),
                        "LOGIN_FAILED", ipAddress, userAgent, traceId,
                        Map.of(
                                "email", maskEmail(email),
                                "reason", "INVALID_CREDENTIALS"
                        ));

                loginFailedCounter.increment();
                obs.event(Observation.Event.of("login.invalid_credentials"));
                // Deliberately vague: same message for wrong pw + unknown email
                throw new InvalidCredentialsException("Invalid credentials");
            }

            User user = userOpt.get();

            if (user.isAccountLocked()) {
                loginFailedCounter.increment();
                obs.event(Observation.Event.of("login.account_locked"));
                throw new AccountLockedException(
                        "Account temporarily locked until: " + user.getLockUntil());
            }

            if (user.isSuspended()) {
                loginFailedCounter.increment();
                obs.event(Observation.Event.of("login.account_suspended"));
                throw new AccountSuspendedException(
                        "Account suspended. Contact support.");
            }

            user.resetFailedLoginAttempts();
            userRepository.save(user);
            sessionCacheRepository.resetFailedAttempts(email);

            JwtIssuer.TokenPair tokenPair =
                    jwtIssuer.issueTokens(user, request.deviceFingerprint());

            Session session = Session.builder()
                    .sessionId(UUID.randomUUID())
                    .userId(user.getUserId())
                    .jti(UUID.fromString(tokenPair.jti()))
                    .deviceFingerprint(request.deviceFingerprint())
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .expiresAt(tokenPair.expiresAt().plus(Duration.ofDays(30)))
                    .build();

            session.setRefreshTokenHash(
                    passwordEncoder.encode(tokenPair.refreshToken()));

            sessionRepository.save(session);
            sessionCacheRepository.invalidate(user.getUserId());

            writeAuditLog(user.getUserId(), "LOGIN_SUCCESS",
                    ipAddress, userAgent, traceId,
                    Map.of(
                            "sessionId", session.getSessionId().toString(),
                            "deviceFingerprint",
                            truncate(request.deviceFingerprint(), 20)
                    ));

            writeOutboxEntry("USER", user.getUserId(), "LoginSuccessful",
                    buildLoginSuccessPayload(user, session, ipAddress));

            loginSuccessCounter.increment();
            obs.event(Observation.Event.of("login.success"));
            log.info("Login successful: userId={} sessionId={} traceId={}",
                    user.getUserId(), session.getSessionId(), traceId);

            return new LoginResponse(
                    tokenPair.accessToken(),
                    tokenPair.refreshToken(),
                    900L, "Bearer",
                    user.getUserId().toString(),
                    user.getRoles()
            );

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // LOGOUT
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void logout(UUID userId, String jti,
                       Instant tokenExpiresAt,
                       String ipAddress, String traceId) {

        sessionRepository.findByJtiAndIsActiveTrue(UUID.fromString(jti))
                .ifPresent(s -> {
                    s.deactivate();
                    sessionRepository.save(s);
                });

        blacklistRepository.blacklist(jti, tokenExpiresAt);
        blacklistRepository.publishRevocationEvent(jti);
        sessionCacheRepository.invalidate(userId);

        writeAuditLog(userId, "LOGOUT", ipAddress,
                null, null, Map.of("jti", jti));

        log.info("Logout: userId={} jti={}", userId, jti);
    }

    // ══════════════════════════════════════════════════════════
    // KYC INITIATION — Java 25 Structured Concurrency
    // ══════════════════════════════════════════════════════════

    @Transactional
    public KycInitiationResponse initiateKyc(
            UUID userId, MultipartFile document,
            String documentType, String ipAddress,
            String traceId) throws Exception {

        Observation obs = Observation.createNotStarted(
                "identity.kyc.initiate", observationRegistry).start();

        try {
            UUID verificationId = UUID.randomUUID();

            String s3Path;
            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner
                            .<Object>awaitAllSuccessfulOrThrow())) {

                // Task A: Redis retry count check (fast ~2ms)
                StructuredTaskScope.Subtask<Integer> retryCheckTask =
                        scope.fork(() -> {
                            int retries = sessionCacheRepository
                                    .getKycRetryCount(userId);
                            if (retries >= 3) {
                                throw new KycRetryLimitExceededException(
                                        "KYC attempt limit (3) reached in 30 days.");
                            }
                            return retries;
                        });

                // Task B: S3 document upload (slow ~500ms–2s)
                StructuredTaskScope.Subtask<String> uploadTask =
                        scope.fork(() -> s3Uploader.uploadKycDocument(
                                userId, verificationId, documentType, document));

                // Blocks; throws FailedException if either task fails
                scope.join();

                s3Path = uploadTask.get();
                log.info("KYC pre-checks passed: userId={} retries={}",
                        userId, retryCheckTask.get());
            }

            KycVerification verification = KycVerification.builder()
                    .verificationId(verificationId)
                    .userId(userId)
                    .attemptNumber(
                            kycRepository.countAttemptsByUserId(userId) + 1)
                    .documentType(documentType)
                    .documentS3Path(s3Path)
                    .documentS3Bucket(System.getenv().getOrDefault(
                            "KYC_DOCUMENTS_BUCKET", "nexus-kyc-documents"))
                    .build();

            kycRepository.save(verification);

            userRepository.findByUserIdAndDeletedAtIsNull(userId)
                    .ifPresent(u -> {
                        u.setStatus(UserStatus.KYC_IN_PROGRESS);
                        userRepository.save(u);
                    });

            writeOutboxEntry("USER", userId, "KycInitiated",
                    buildKycInitiatedPayload(userId, verificationId,
                            documentType, s3Path));

            sqsPublisher.publishKycDocumentForAnalysis(
                    userId, verificationId, s3Path, documentType);

            sessionCacheRepository.incrementKycRetryCount(userId);

            writeAuditLog(userId, "KYC_INITIATED", ipAddress, null, traceId,
                    Map.of(
                            "verificationId", verificationId.toString(),
                            "documentType", documentType,
                            "attemptNumber", verification.getAttemptNumber()
                    ));

            kycInitiatedCounter.increment();
            obs.event(Observation.Event.of("kyc.initiated"));
            log.info("KYC initiated: userId={} verificationId={} traceId={}",
                    userId, verificationId, traceId);

            return new KycInitiationResponse(
                    verificationId.toString(),
                    "KYC verification initiated. " +
                            "You will be notified when complete."
            );

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // KYC RESULT PROCESSING
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void processKycResult(UUID userId, UUID verificationId,
                                 KycResultRequest result,
                                 String traceId) {

        Observation obs = Observation.createNotStarted(
                "identity.kyc.result", observationRegistry).start();

        try {
            KycVerification verification = kycRepository
                    .findByVerificationId(verificationId)
                    .orElseThrow(() -> new RuntimeException(
                            "Verification not found: " + verificationId));

            User user = userRepository
                    .findByUserIdAndDeletedAtIsNull(userId)
                    .orElseThrow(() -> new RuntimeException(
                            "User not found: " + userId));

            verification.setAiExtractedData(result.extractedData());
            verification.setAiVerificationDecision(result.verificationDecision());
            verification.setDecidedAt(Instant.now());
            verification.setCompletedAt(Instant.now());

            if (result.approved()) {
                verification.setFinalDecision(KycDecision.APPROVED);
                user.approveKyc();

                writeOutboxEntry("USER", userId, "IdentityVerified",
                        buildIdentityVerifiedPayload(userId, verificationId));

                writeAuditLog(userId, "KYC_APPROVED", null, null, traceId,
                        Map.of(
                                "verificationId", verificationId.toString(),
                                "documentType", verification.getDocumentType()
                        ));

                obs.event(Observation.Event.of("kyc.approved"));
                log.info("KYC approved: userId={} verificationId={}",
                        userId, verificationId);

            } else {
                verification.setFinalDecision(KycDecision.REJECTED);
                verification.setFailureReasons(result.failureReasons());

                int attempts = kycRepository.countAttemptsByUserId(userId);
                boolean permanent = attempts >= 3;

                if (permanent) user.permanentlyRejectKyc();
                else user.rejectKyc();

                String userMessage = rejectionExplainer
                        .explain(result.failureReasons(), "es");

                writeOutboxEntry("USER", userId, "IdentityRejected",
                        buildIdentityRejectedPayload(
                                userId, verificationId, result.failureReasons(),
                                attempts, 3 - attempts, userMessage, permanent));

                writeAuditLog(userId, "KYC_REJECTED", null, null, traceId,
                        Map.of(
                                "verificationId", verificationId.toString(),
                                "failureReasons", result.failureReasons(),
                                "isPermanent", permanent
                        ));

                obs.event(Observation.Event.of("kyc.rejected"));
                log.info("KYC rejected: userId={} permanent={} reasons={}",
                        userId, permanent, result.failureReasons());
            }

            kycRepository.save(verification);
            userRepository.save(user);

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // CHANGE PASSWORD
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void changePassword(UUID userId,
                               ChangePasswordRequest request,
                               UUID currentSessionId,
                               String ipAddress,
                               String traceId) {

        User user = userRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        boolean valid = bcryptTimer.record(() ->
                passwordEncoder.matches(request.currentPassword(),
                        user.getPasswordHash()));

        if (!valid) {
            writeAuditLog(userId, "PASSWORD_CHANGE_FAILED",
                    ipAddress, null, traceId,
                    Map.of("reason", "WRONG_CURRENT_PASSWORD"));
            throw new InvalidCredentialsException(
                    "Current password is incorrect");
        }

        List<PasswordHistory> history = passwordHistoryRepository
                .findTop5ByUserIdOrderByCreatedAtDesc(userId);

        boolean reused = history.stream().anyMatch(ph ->
                passwordEncoder.matches(request.newPassword(),
                        ph.getPasswordHash()));

        if (reused) {
            throw new PasswordReusedException(
                    "Cannot reuse one of your last 5 passwords");
        }

        String newHash = bcryptTimer.record(() ->
                passwordEncoder.encode(request.newPassword()));

        user.setPasswordHash(newHash);
        userRepository.save(user);
        savePasswordHistory(userId, newHash);

        List<Session> activeSessions =
                sessionRepository.findActiveSessionsForUser(userId);

        activeSessions.stream()
                .filter(s -> !s.getSessionId().equals(currentSessionId))
                .forEach(s -> {
                    s.deactivate();
                    blacklistRepository.blacklist(
                            s.getJti().toString(), s.getExpiresAt());
                    blacklistRepository.publishRevocationEvent(
                            s.getJti().toString());
                });

        sessionRepository.saveAll(activeSessions);
        sessionCacheRepository.invalidate(userId);

        writeOutboxEntry("USER", userId, "PasswordChanged",
                objectMapper.createObjectNode()
                        .put("userId", userId.toString())
                        .put("changedAt", Instant.now().toString()));

        writeAuditLog(userId, "PASSWORD_CHANGED", ipAddress, null, traceId,
                Map.of("sessionsRevoked", activeSessions.size() - 1));

        log.info("Password changed: userId={} sessionsRevoked={}",
                userId, activeSessions.size() - 1);
    }

    // ══════════════════════════════════════════════════════════
    // PASSWORD RESET — REQUEST
    // ══════════════════════════════════════════════════════════

    /**
     * Initiates a password reset flow for the given email.
     *
     * Security design (all three below combine to prevent user enumeration):
     *   1. AuthController always returns HTTP 200 regardless of outcome.
     *   2. This method never throws for "email not found" — it logs and returns.
     *   3. The method IS allowed to throw for infrastructure failures (S3, Redis,
     *      DB) because AuthController suppresses all exceptions in its try-catch.
     *
     * Token:   256-bit cryptographically random, URL-safe base64 (~43 chars).
     * Storage: Redis key=pwreset:{token}, value=userId.toString(), TTL=30 min.
     * Delivery: outbox PasswordResetRequested event → notification-service
     *           sends the email. This service never calls SMTP directly.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        Observation obs = Observation.createNotStarted(
                "identity.password.reset.request", observationRegistry).start();

        try {
            String normalizedEmail = email.toLowerCase().trim();

            Optional<User> userOpt = userRepository
                    .findByEmailIgnoreCaseAndDeletedAtIsNull(normalizedEmail);

            if (userOpt.isEmpty()) {
                // Silently ignore — never reveal whether email is registered
                log.debug("Password reset for unknown email: {}",
                        maskEmail(normalizedEmail));
                obs.event(Observation.Event.of(
                        "password.reset.unknown_email"));
                return;
            }

            User user = userOpt.get();

            // 256-bit token
            byte[] tokenBytes = new byte[32];
            SecureRandom.getInstanceStrong().nextBytes(tokenBytes);
            String resetToken = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(tokenBytes);

            // Store in Redis (one-time, 30-minute TTL)
            sessionCacheRepository.storePasswordResetToken(
                    resetToken, user.getUserId(), PASSWORD_RESET_TOKEN_TTL);

            // Outbox → notification-service sends the email
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("userId",    user.getUserId().toString())
                    .put("email",     user.getEmail())
                    .put("resetToken", resetToken)
                    .put("expiresAt",
                            Instant.now().plus(PASSWORD_RESET_TOKEN_TTL).toString())
                    .put("requestedAt", Instant.now().toString());

            writeOutboxEntry("USER", user.getUserId(),
                    "PasswordResetRequested", payload);

            writeAuditLog(user.getUserId(), "PASSWORD_RESET_REQUESTED",
                    null, null, null,
                    Map.of("email", maskEmail(normalizedEmail)));

            passwordResetRequestCounter.increment();
            obs.event(Observation.Event.of("password.reset.requested"));
            log.info("Password reset requested: userId={} email={}",
                    user.getUserId(), maskEmail(normalizedEmail));

        } catch (Exception e) {
            obs.error(e);
            log.error("Password reset request error: email={} {}",
                    maskEmail(email), e.getMessage(), e);
            throw new RuntimeException("Password reset request failed", e);
        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // PASSWORD RESET — CONFIRM
    // ══════════════════════════════════════════════════════════

    /**
     * Confirms a password reset using the one-time token.
     *
     * Steps:
     *   1. Validate token in Redis (exists + not expired)
     *   2. Load user by userId stored in Redis value
     *   3. Check new password against last 5 (reuse prevention)
     *   4. BCrypt hash + update user row
     *   5. Add to password history
     *   6. Revoke ALL sessions (full re-auth required after reset)
     *   7. Delete token from Redis (one-time use enforced)
     *   8. Outbox PasswordResetCompleted + audit log
     */
    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        Observation obs = Observation.createNotStarted(
                "identity.password.reset.confirm", observationRegistry).start();

        try {
            UUID userId = sessionCacheRepository
                    .resolvePasswordResetToken(token)
                    .orElseThrow(() ->
                            new InvalidPasswordResetTokenException(
                                    "Password reset token is invalid or has expired"));

            User user = userRepository.findByUserIdAndDeletedAtIsNull(userId)
                    .orElseThrow(() ->
                            new UserNotFoundException("User not found"));

            List<PasswordHistory> history = passwordHistoryRepository
                    .findTop5ByUserIdOrderByCreatedAtDesc(userId);

            boolean reused = history.stream().anyMatch(ph ->
                    passwordEncoder.matches(newPassword, ph.getPasswordHash()));

            if (reused) {
                throw new PasswordReusedException(
                        "Cannot reuse one of your last 5 passwords");
            }

            String newHash = bcryptTimer.record(() ->
                    passwordEncoder.encode(newPassword));

            user.setPasswordHash(newHash);
            userRepository.save(user);
            savePasswordHistory(userId, newHash);

            // Revoke every active session — password reset = full logout
            List<Session> sessions =
                    sessionRepository.findActiveSessionsForUser(userId);

            sessions.forEach(s -> {
                s.deactivate();
                blacklistRepository.blacklist(
                        s.getJti().toString(), s.getExpiresAt());
                blacklistRepository.publishRevocationEvent(
                        s.getJti().toString());
            });

            sessionRepository.saveAll(sessions);
            sessionCacheRepository.invalidate(userId);

            // Burn the token (one-time use)
            sessionCacheRepository.deletePasswordResetToken(token);

            writeOutboxEntry("USER", userId, "PasswordResetCompleted",
                    objectMapper.createObjectNode()
                            .put("userId", userId.toString())
                            .put("completedAt", Instant.now().toString()));

            writeAuditLog(userId, "PASSWORD_RESET_COMPLETED",
                    null, null, null,
                    Map.of("sessionsRevoked", sessions.size()));

            obs.event(Observation.Event.of("password.reset.confirmed"));
            log.info("Password reset confirmed: userId={} sessionsRevoked={}",
                    userId, sessions.size());

        } catch (InvalidPasswordResetTokenException
                 | PasswordReusedException e) {
            obs.event(Observation.Event.of("password.reset.failed"));
            throw e;
        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ══════════════════════════════════════════════════════════

    /**
     * Terminates a specific session (user-initiated from device list).
     * Gateway enforces that X-User-Id header matches the requesting user;
     * this method adds a second check to prevent cross-user termination.
     */
    @Transactional
    public void terminateSession(UUID userId, UUID sessionId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            if (!session.getUserId().equals(userId)) {
                throw new UnauthorizedException(
                        "Cannot terminate another user's session");
            }
            session.deactivate();
            blacklistRepository.blacklist(
                    session.getJti().toString(), session.getExpiresAt());
            blacklistRepository.publishRevocationEvent(
                    session.getJti().toString());
            sessionRepository.save(session);
            sessionCacheRepository.invalidate(userId);

            log.info("Session terminated: userId={} sessionId={}",
                    userId, sessionId);
        });
    }

    // ══════════════════════════════════════════════════════════
    // SAGA COMPENSATION
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void cancelRegistration(UUID userId, String sagaId,
                                   String traceId) {

        log.info("SAGA compensation: cancelling registration " +
                "userId={} sagaId={}", userId, sagaId);

        userRepository.findByUserIdAndDeletedAtIsNull(userId)
                .ifPresent(user -> {
                    if (user.getStatus() == UserStatus.REGISTRATION_CANCELLED) {
                        log.info("Already cancelled (idempotent): userId={}",
                                userId);
                        return;
                    }

                    user.cancelRegistration();
                    userRepository.save(user);

                    List<Session> sessions =
                            sessionRepository.findActiveSessionsForUser(userId);
                    sessions.forEach(s -> {
                        s.deactivate();
                        blacklistRepository.blacklist(
                                s.getJti().toString(), s.getExpiresAt());
                    });
                    sessionRepository.saveAll(sessions);
                    sessionCacheRepository.invalidate(userId);

                    writeOutboxEntry("USER", userId,
                            "UserRegistrationCancelled",
                            objectMapper.createObjectNode()
                                    .put("userId", userId.toString())
                                    .put("sagaId", sagaId)
                                    .put("cancelledAt", Instant.now().toString()));

                    writeAuditLog(userId, "REGISTRATION_CANCELLED",
                            null, null, traceId,
                            Map.of("sagaId", sagaId));
                });
    }

    // ══════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════

    private void writeOutboxEntry(String aggregateType,
                                  UUID aggregateId,
                                  String eventType,
                                  ObjectNode payload) {
        outboxRepository.save(
                OutboxEntry.of(aggregateType, aggregateId, eventType, payload));
    }

    @Transactional
    public void writeAuditLog(UUID userId, String eventType,
                              String ipAddress, String userAgent,
                              String traceId,
                              Map<String, Object> details) {
        AuditLog entry = AuditLog.builder()
                .auditId(UUID.randomUUID())
                .userId(userId)
                .eventType(eventType)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .traceId(traceId)
                .details(details != null
                        ? objectMapper.valueToTree(details) : null)
                .build();
        auditRepository.save(entry);
    }

    private void savePasswordHistory(UUID userId, String hash) {
        passwordHistoryRepository.save(PasswordHistory.builder()
                .historyId(UUID.randomUUID())
                .userId(userId)
                .passwordHash(hash)
                .build());
    }

    private ObjectNode buildUserRegisteredPayload(User user) {
        return objectMapper.createObjectNode()
                .put("userId",      user.getUserId().toString())
                .put("email",       user.getEmail())
                .put("fullName",    user.getFullName())
                .put("phoneNumber", user.getPhoneNumber())
                .put("country",     user.getCountry())
                .put("createdAt",   user.getCreatedAt().toString());
    }

    private ObjectNode buildLoginSuccessPayload(User user,
                                                Session session,
                                                String ipAddress) {
        return objectMapper.createObjectNode()
                .put("userId",            user.getUserId().toString())
                .put("sessionId",         session.getSessionId().toString())
                .put("ipAddress",         ipAddress)
                .put("deviceFingerprint", session.getDeviceFingerprint())
                .put("loginAt",           Instant.now().toString());
    }

    private ObjectNode buildKycInitiatedPayload(UUID userId,
                                                UUID verificationId,
                                                String documentType,
                                                String s3Path) {
        return objectMapper.createObjectNode()
                .put("userId",          userId.toString())
                .put("verificationId",  verificationId.toString())
                .put("documentType",    documentType)
                .put("s3Path",          s3Path)
                .put("initiatedAt",     Instant.now().toString());
    }

    private ObjectNode buildIdentityVerifiedPayload(UUID userId,
                                                    UUID verificationId) {
        return objectMapper.createObjectNode()
                .put("userId",         userId.toString())
                .put("verificationId", verificationId.toString())
                .put("verifiedAt",     Instant.now().toString());
    }

    private ObjectNode buildIdentityRejectedPayload(
            UUID userId, UUID verificationId,
            List<String> reasons, int attempt, int remaining,
            String userMessage, boolean permanent) {

        var node = objectMapper.createObjectNode()
                .put("userId",            userId.toString())
                .put("verificationId",    verificationId.toString())
                .put("attempt",           attempt)
                .put("attemptsRemaining", remaining)
                .put("userMessage",       userMessage)
                .put("isPermanent",       permanent)
                .put("rejectedAt",        Instant.now().toString());

        var reasonsArray = objectMapper.createArrayNode();
        reasons.forEach(reasonsArray::add);
        node.set("failureReasons", reasonsArray);
        return node;
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        String[] parts = email.split("@");
        String local = parts[0];
        return (local.length() > 1
                ? local.charAt(0) + "***" : "***")
                + "@" + parts[1];
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}