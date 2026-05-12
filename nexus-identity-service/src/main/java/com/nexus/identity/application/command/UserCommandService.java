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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;

/**
 * User Command Service — CQRS Write Side.
 *
 * Handles all state-changing operations:
 * - User registration with Outbox Pattern
 * - Login with BCrypt verification + JWT issuance
 * - Password change with history check
 * - KYC initiation with Structured Concurrency (Java 25)
 * - Session management
 * - SAGA compensation (cancel registration)
 *
 * All writes use @Transactional to ensure:
 * domain table write + outbox write = atomic unit
 * Either both succeed or neither does (Outbox Pattern guarantee)
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

    // Metrics
    private final Counter registrationSuccessCounter;
    private final Counter registrationFailedCounter;
    private final Counter loginSuccessCounter;
    private final Counter loginFailedCounter;
    private final Counter kycInitiatedCounter;
    private final Timer bcryptTimer;

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

        this.registrationSuccessCounter = Counter.builder("identity.registrations")
                .tag("outcome", "success").register(meterRegistry);
        this.registrationFailedCounter = Counter.builder("identity.registrations")
                .tag("outcome", "failed").register(meterRegistry);
        this.loginSuccessCounter = Counter.builder("identity.logins")
                .tag("outcome", "success").register(meterRegistry);
        this.loginFailedCounter = Counter.builder("identity.logins")
                .tag("outcome", "failed").register(meterRegistry);
        this.kycInitiatedCounter = Counter.builder("identity.kyc.initiations")
                .register(meterRegistry);
        this.bcryptTimer = Timer.builder("identity.bcrypt.duration")
                .description("BCrypt hash/verify duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
    }

    // ══════════════════════════════════════════════════════════
    // REGISTRATION
    // ══════════════════════════════════════════════════════════

    /**
     * Registers a new user.
     *
     * Atomic transaction:
     * 1. Validate uniqueness
     * 2. Hash password with BCrypt
     * 3. INSERT users row
     * 4. INSERT outbox row (UserRegistered event)
     * 5. INSERT audit_log row
     * 6. INSERT password_history row
     *
     * Debezium reads outbox → publishes to Kafka users.registered
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request, String ipAddress,
                                     String userAgent, String traceId) {

        Observation obs = Observation.createNotStarted(
                "identity.register", observationRegistry).start();

        try {
            // Domain validation — uniqueness checks
            if (userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(
                    request.email())) {
                registrationFailedCounter.increment();
                throw new DuplicateEmailException(
                        "Email already registered: " +
                                maskEmail(request.email()));
            }

            if (userRepository.existsByPhoneNumberAndDeletedAtIsNull(
                    request.phoneNumber())) {
                registrationFailedCounter.increment();
                throw new DuplicatePhoneException(
                        "Phone number already registered");
            }

            // BCrypt password hashing (timing tracked by Micrometer)
            String passwordHash = bcryptTimer.record(() ->
                    passwordEncoder.encode(request.password()));

            // Build user aggregate
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

            // Password history (first entry)
            savePasswordHistory(user.getUserId(), passwordHash);

            // Outbox entry — Debezium publishes UserRegistered to Kafka
            writeOutboxEntry(
                    "USER",
                    user.getUserId(),
                    "UserRegistered",
                    buildUserRegisteredPayload(user)
            );

            // Audit log
            writeAuditLog(
                    user.getUserId(),
                    "USER_REGISTERED",
                    ipAddress, userAgent, traceId,
                    Map.of(
                            "email", maskEmail(user.getEmail()),
                            "country", user.getCountry()
                    )
            );

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

    /**
     * Authenticates a user and issues JWT + refresh token.
     *
     * Security measures:
     * - Redis failed attempt check (fast pre-rejection for brute force)
     * - BCrypt timing-safe comparison (300ms — makes brute force slow)
     * - Account status check (SUSPENDED, LOCKED)
     * - Outbox event for risk scoring (LoginSuccessful with IP/device)
     * - Constant-time response for wrong password vs unknown email
     *   (prevents user enumeration via timing)
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress,
                               String userAgent, String traceId) {

        Observation obs = Observation.createNotStarted(
                        "identity.login", observationRegistry)
                .lowCardinalityKeyValue("outcome", "pending")
                .start();

        try {
            String email = request.email().toLowerCase();

            // Fast pre-rejection: Redis failed attempt counter
            int redisFailures = sessionCacheRepository.getFailedAttempts(email);
            if (redisFailures >= 5) {
                loginFailedCounter.increment();
                obs.event(Observation.Event.of("login.rate_limited"));
                throw new AccountLockedException(
                        "Too many failed attempts. Try again later.");
            }

            // Load user — deliberately vague error message
            Optional<User> userOpt = userRepository
                    .findByEmailIgnoreCaseAndDeletedAtIsNull(email);

            // BCrypt hash verification — always run even if user not found
            // (prevents timing attack that reveals whether email exists)
            String hashToCompare = userOpt
                    .map(User::getPasswordHash)
                    .orElse("$2a$12$dummy.hash.for.timing.safety.xxxxx");

            boolean passwordMatches = bcryptTimer.record(() ->
                    passwordEncoder.matches(request.password(), hashToCompare));

            if (userOpt.isEmpty() || !passwordMatches) {
                // Handle failed attempt tracking
                userOpt.ifPresent(u -> {
                    u.incrementFailedLoginAttempts();
                    if (u.getFailedLoginAttempts() >= 5) {
                        u.lockAccount(Instant.now()
                                .plus(java.time.Duration.ofMinutes(15)));
                    }
                    userRepository.save(u);
                });

                sessionCacheRepository.incrementFailedAttempts(email);

                writeAuditLog(
                        userOpt.map(User::getUserId).orElse(null),
                        "LOGIN_FAILED",
                        ipAddress, userAgent, traceId,
                        Map.of(
                                "email", maskEmail(email),
                                "reason", "INVALID_CREDENTIALS"
                        )
                );

                loginFailedCounter.increment();
                obs.event(Observation.Event.of("login.invalid_credentials"));

                throw new InvalidCredentialsException(
                        "Invalid credentials"); // Deliberately vague
            }

            User user = userOpt.get();

            // Account status checks
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

            // Successful login — reset failed attempts
            user.resetFailedLoginAttempts();
            userRepository.save(user);
            sessionCacheRepository.resetFailedAttempts(email);

            // Issue JWT + refresh token
            JwtIssuer.TokenPair tokenPair = jwtIssuer.issueTokens(
                    user, request.deviceFingerprint());

            // Store session
            Session session = Session.builder()
                    .sessionId(UUID.randomUUID())
                    .userId(user.getUserId())
                    .jti(UUID.fromString(tokenPair.jti()))
                    .deviceFingerprint(request.deviceFingerprint())
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .expiresAt(tokenPair.expiresAt()
                            .plus(java.time.Duration.ofDays(30))) // Refresh token duration
                    .build();

            // BCrypt the refresh token for storage
            String refreshTokenHash = passwordEncoder.encode(
                    tokenPair.refreshToken());
            session.setRefreshTokenHash(refreshTokenHash);

            sessionRepository.save(session);

            // Invalidate session cache
            sessionCacheRepository.invalidate(user.getUserId());

            // Audit log + outbox events
            writeAuditLog(
                    user.getUserId(),
                    "LOGIN_SUCCESS",
                    ipAddress, userAgent, traceId,
                    Map.of(
                            "sessionId", session.getSessionId().toString(),
                            "deviceFingerprint",
                            truncate(request.deviceFingerprint(), 20)
                    )
            );

            writeOutboxEntry(
                    "USER",
                    user.getUserId(),
                    "LoginSuccessful",
                    buildLoginSuccessPayload(user, session, ipAddress)
            );

            loginSuccessCounter.increment();
            obs.event(Observation.Event.of("login.success"));

            log.info("Login successful: userId={} sessionId={} traceId={}",
                    user.getUserId(), session.getSessionId(), traceId);

            return new LoginResponse(
                    tokenPair.accessToken(),
                    tokenPair.refreshToken(),
                    900L,
                    "Bearer",
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
    public void logout(UUID userId, String jti, Instant tokenExpiresAt,
                       String ipAddress, String traceId) {

        // Find and deactivate session
        Optional<Session> session = sessionRepository
                .findByJtiAndIsActiveTrue(UUID.fromString(jti));

        session.ifPresent(s -> {
            s.deactivate();
            sessionRepository.save(s);
        });

        // Blacklist the JWT immediately
        blacklistRepository.blacklist(jti, tokenExpiresAt);
        blacklistRepository.publishRevocationEvent(jti);

        // Invalidate Redis session cache
        sessionCacheRepository.invalidate(userId);

        // Audit log
        writeAuditLog(userId, "LOGOUT", ipAddress, null, null,
                Map.of("jti", jti));

        log.info("Logout: userId={} jti={}", userId, jti);
    }

    // ══════════════════════════════════════════════════════════
    // KYC INITIATION — Java 25 Structured Concurrency
    // ══════════════════════════════════════════════════════════

    /**
     * Initiates KYC verification flow.
     *
     * Uses Java 25 Structured Concurrency (ShutdownOnFailure):
     * - Operation A: Check Redis KYC retry counter (fast)
     * - Operation B: Upload document to AWS S3 (slow, I/O)
     *
     * If EITHER fails → both cancelled, scope closed, exception thrown.
     * PostgreSQL write only happens AFTER both succeed.
     *
     * This prevents consuming a retry attempt on S3 failure.
     */
    @Transactional
    public KycInitiationResponse initiateKyc(
            UUID userId, MultipartFile document,
            String documentType, String ipAddress,
            String traceId) throws Exception {

        Observation obs = Observation.createNotStarted(
                "identity.kyc.initiate", observationRegistry).start();

        try {
            UUID verificationId = UUID.randomUUID();

            // Parallel operations with Structured Concurrency
            String s3Path;
            try (var scope =
                         new StructuredTaskScope.ShutdownOnFailure()) {

                // Task A: Check retry limit in Redis
                StructuredTaskScope.Subtask<Integer> retryCheckTask =
                        scope.fork(() -> {
                            int retries = sessionCacheRepository
                                    .getKycRetryCount(userId);
                            if (retries >= 3) {
                                throw new KycRetryLimitExceededException(
                                        "KYC attempt limit (3) reached in 30 days. " +
                                                "Contact support.");
                            }
                            return retries;
                        });

                // Task B: Upload document to S3
                StructuredTaskScope.Subtask<String> uploadTask =
                        scope.fork(() -> s3Uploader.uploadKycDocument(
                                userId, verificationId, documentType, document));

                // Wait for both — throws if either fails
                scope.join().throwIfFailed();

                s3Path = uploadTask.get();

                log.info("KYC pre-checks passed: userId={} retries={}",
                        userId, retryCheckTask.get());
            }

            // Both operations succeeded — persist to DB
            KycVerification verification = KycVerification.builder()
                    .verificationId(verificationId)
                    .userId(userId)
                    .attemptNumber(
                            kycRepository.countAttemptsByUserId(userId) + 1)
                    .documentType(documentType)
                    .documentS3Path(s3Path)
                    .documentS3Bucket(
                            System.getenv().getOrDefault(
                                    "KYC_DOCUMENTS_BUCKET", "nexus-kyc-documents"))
                    .build();

            kycRepository.save(verification);

            // Update user status to KYC_IN_PROGRESS
            userRepository.findByUserIdAndDeletedAtIsNull(userId)
                    .ifPresent(u -> {
                        u.setStatus(UserStatus.KYC_IN_PROGRESS);
                        userRepository.save(u);
                    });

            // Outbox event for audit
            writeOutboxEntry("USER", userId, "KycInitiated",
                    buildKycInitiatedPayload(userId, verificationId,
                            documentType, s3Path));

            // SQS publish to trigger Rekognition Lambda
            sqsPublisher.publishKycDocumentForAnalysis(
                    userId, verificationId, s3Path, documentType);

            // Increment retry counter AFTER successful initiation
            sessionCacheRepository.incrementKycRetryCount(userId);

            // Audit log
            writeAuditLog(userId, "KYC_INITIATED", ipAddress, null,
                    traceId, Map.of(
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
                    "KYC verification initiated. You will be notified when complete."
            );

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // KYC RESULT PROCESSING (called by AI KYC Service)
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
                // KYC APPROVED
                verification.setFinalDecision(KycDecision.APPROVED);
                user.approveKyc();

                writeOutboxEntry("USER", userId, "IdentityVerified",
                        buildIdentityVerifiedPayload(userId, verificationId));

                writeAuditLog(userId, "KYC_APPROVED", null, null,
                        traceId, Map.of(
                                "verificationId", verificationId.toString(),
                                "documentType", verification.getDocumentType()
                        ));

                obs.event(Observation.Event.of("kyc.approved"));
                log.info("KYC approved: userId={} verificationId={}",
                        userId, verificationId);

            } else {
                // KYC REJECTED
                verification.setFinalDecision(KycDecision.REJECTED);
                verification.setFailureReasons(result.failureReasons());

                int attempts = kycRepository.countAttemptsByUserId(userId);
                boolean permanent = attempts >= 3;

                if (permanent) {
                    user.permanentlyRejectKyc();
                } else {
                    user.rejectKyc();
                }

                // Spring AI: translate technical codes → user message
                String userMessage = rejectionExplainer
                        .explain(result.failureReasons(), "es");

                writeOutboxEntry("USER", userId, "IdentityRejected",
                        buildIdentityRejectedPayload(
                                userId, verificationId,
                                result.failureReasons(),
                                attempts, 3 - attempts,
                                userMessage, permanent
                        ));

                writeAuditLog(userId, "KYC_REJECTED", null, null,
                        traceId, Map.of(
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
    public void changePassword(UUID userId, ChangePasswordRequest request,
                               UUID currentSessionId, String ipAddress,
                               String traceId) {

        User user = userRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Verify current password
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

        // Check password history (last 5)
        List<PasswordHistory> history = passwordHistoryRepository
                .findTop5ByUserIdOrderByCreatedAtDesc(userId);

        boolean reused = history.stream().anyMatch(ph ->
                passwordEncoder.matches(request.newPassword(),
                        ph.getPasswordHash()));

        if (reused) {
            throw new PasswordReusedException(
                    "Cannot reuse one of your last 5 passwords");
        }

        // Hash new password and update
        String newHash = bcryptTimer.record(() ->
                passwordEncoder.encode(request.newPassword()));

        user.setPasswordHash(newHash);
        userRepository.save(user);

        // Save to history
        savePasswordHistory(userId, newHash);

        // Invalidate ALL other sessions (security: password change = logout everywhere)
        List<Session> activeSessions = sessionRepository
                .findActiveSessionsForUser(userId);

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

        // Outbox + audit
        writeOutboxEntry("USER", userId, "PasswordChanged",
                objectMapper.createObjectNode()
                        .put("userId", userId.toString())
                        .put("changedAt", Instant.now().toString()));

        writeAuditLog(userId, "PASSWORD_CHANGED", ipAddress, null,
                traceId, Map.of(
                        "sessionsRevoked", activeSessions.size() - 1
                ));

        log.info("Password changed: userId={} sessionsRevoked={}",
                userId, activeSessions.size() - 1);
    }

    // ══════════════════════════════════════════════════════════
    // SAGA COMPENSATION — CancelUserRegistrationCommand
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void cancelRegistration(UUID userId, String sagaId,
                                   String traceId) {

        log.info("SAGA compensation: cancelling registration " +
                "userId={} sagaId={}", userId, sagaId);

        userRepository.findByUserIdAndDeletedAtIsNull(userId)
                .ifPresent(user -> {

                    // Idempotency: already cancelled?
                    if (user.getStatus() == UserStatus.REGISTRATION_CANCELLED) {
                        log.info("Registration already cancelled: userId={}",
                                userId);
                        return;
                    }

                    user.cancelRegistration();
                    userRepository.save(user);

                    // Revoke all active sessions
                    List<Session> sessions = sessionRepository
                            .findActiveSessionsForUser(userId);
                    sessions.forEach(s -> {
                        s.deactivate();
                        blacklistRepository.blacklist(
                                s.getJti().toString(), s.getExpiresAt());
                    });
                    sessionRepository.saveAll(sessions);
                    sessionCacheRepository.invalidate(userId);

                    // Outbox event
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

    private void writeOutboxEntry(String aggregateType, UUID aggregateId,
                                  String eventType, ObjectNode payload) {
        outboxRepository.save(OutboxEntry.of(
                aggregateType, aggregateId, eventType, payload));
    }

    @Transactional
    public void writeAuditLog(UUID userId, String eventType,
                              String ipAddress, String userAgent,
                              String traceId, Map<String, Object> details) {
        AuditLog entry = AuditLog.builder()
                .auditId(UUID.randomUUID())
                .userId(userId)
                .eventType(eventType)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .traceId(traceId)
                .details(details != null
                        ? objectMapper.valueToTree(details)
                        : null)
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
                .put("userId", user.getUserId().toString())
                .put("email", user.getEmail())
                .put("fullName", user.getFullName())
                .put("phoneNumber", user.getPhoneNumber())
                .put("country", user.getCountry())
                .put("createdAt", user.getCreatedAt().toString());
    }

    private ObjectNode buildLoginSuccessPayload(User user, Session session,
                                                String ipAddress) {
        return objectMapper.createObjectNode()
                .put("userId", user.getUserId().toString())
                .put("sessionId", session.getSessionId().toString())
                .put("ipAddress", ipAddress)
                .put("deviceFingerprint", session.getDeviceFingerprint())
                .put("loginAt", Instant.now().toString());
    }

    private ObjectNode buildKycInitiatedPayload(UUID userId,
                                                UUID verificationId,
                                                String documentType,
                                                String s3Path) {
        return objectMapper.createObjectNode()
                .put("userId", userId.toString())
                .put("verificationId", verificationId.toString())
                .put("documentType", documentType)
                .put("s3Path", s3Path)
                .put("initiatedAt", Instant.now().toString());
    }

    private ObjectNode buildIdentityVerifiedPayload(UUID userId,
                                                    UUID verificationId) {
        return objectMapper.createObjectNode()
                .put("userId", userId.toString())
                .put("verificationId", verificationId.toString())
                .put("verifiedAt", Instant.now().toString());
    }

    private ObjectNode buildIdentityRejectedPayload(
            UUID userId, UUID verificationId,
            List<String> reasons, int attempt, int remaining,
            String userMessage, boolean permanent) {

        var node = objectMapper.createObjectNode()
                .put("userId", userId.toString())
                .put("verificationId", verificationId.toString())
                .put("attempt", attempt)
                .put("attemptsRemaining", remaining)
                .put("userMessage", userMessage)
                .put("isPermanent", permanent)
                .put("rejectedAt", Instant.now().toString());

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
                ? local.charAt(0) + "***"
                : "***")
                + "@" + parts[1];
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}