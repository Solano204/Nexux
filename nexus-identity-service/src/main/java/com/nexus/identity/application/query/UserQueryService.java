package com.nexus.identity.application.query;

import com.nexus.identity.application.command.UserNotFoundException;
import com.nexus.identity.domain.model.KycVerification;
import com.nexus.identity.domain.model.Session;
import com.nexus.identity.domain.model.User;
import com.nexus.identity.infrastructure.persistence.KycVerificationRepository;
import com.nexus.identity.infrastructure.persistence.SessionRepository;
import com.nexus.identity.infrastructure.persistence.UserRepository;
import com.nexus.identity.infrastructure.redis.SessionCacheRepository;
import com.nexus.identity.web.dto.response.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User Query Service — CQRS Read Side.
 *
 * All methods are read-only:
 * - @Transactional(readOnly = true) — PostgreSQL read hint
 * - Redis cache checked first (5-minute TTL for profiles)
 * - Returns DTOs (NOT domain entities — controlled projection)
 *
 * Pattern: Repository Pattern — abstracts storage from domain
 * Pattern: CQRS — completely separate from command operations
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryService {

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final KycVerificationRepository kycRepository;
    private final SessionCacheRepository sessionCacheRepository;
    private final ObservationRegistry observationRegistry;

    /**
     * Get current user profile.
     * Cached in Redis for 60 seconds per userId.
     */
    @Cacheable(value = "user-profile", key = "#userId")
    public UserProfileResponse getUserProfile(UUID userId) {
        Observation obs = Observation.createNotStarted(
                "identity.query.profile", observationRegistry).start();

        try {
            User user = userRepository.findByUserIdAndDeletedAtIsNull(userId)
                    .orElseThrow(() -> new UserNotFoundException(
                            "User not found: " + userId));

            return mapToProfileResponse(user);
        } finally {
            obs.stop();
        }
    }

    /**
     * Get active sessions for user.
     * Cached in Redis for 5 minutes.
     */
    public List<SessionSummaryResponse> getActiveSessions(UUID userId) {
        List<Map<String, Object>> cached =
                sessionCacheRepository.getCachedSessions(userId);

        if (cached != null) {
            return cached.stream()
                    .map(this::mapToSessionSummary)
                    .toList();
        }

        // Cache miss — query PostgreSQL
        List<Session> sessions = sessionRepository
                .findActiveSessionsForUser(userId);

        List<SessionSummaryResponse> responses = sessions.stream()
                .map(this::mapToSessionResponse)
                .toList();

        // Populate cache (as list of maps for JSON serialization)
        sessionCacheRepository.cacheActiveSessions(userId,
                sessions.stream()
                        .map(s -> Map.of(
                                "sessionId", s.getSessionId().toString(),
                                "ipAddress", s.getIpAddress() != null
                                        ? s.getIpAddress() : "unknown",
                                "deviceFingerprint",
                                s.getDeviceFingerprint() != null
                                        ? s.getDeviceFingerprint() : "",
                                "issuedAt", s.getIssuedAt().toString(),
                                "lastActivityAt", s.getLastActivityAt().toString()
                        ))
                        .map(m -> (Map<String, Object>) (Map<?,?>) m)
                        .toList()
        );

        return responses;
    }

    /**
     * Get KYC verification history for user.
     */
    public List<KycStatusResponse> getKycHistory(UUID userId) {
        return kycRepository.findByUserIdOrderByInitiatedAtDesc(userId)
                .stream()
                .map(this::mapToKycResponse)
                .toList();
    }

    /**
     * Get current KYC status.
     */
    public KycStatusResponse getCurrentKycStatus(UUID userId) {
        return kycRepository
                .findTopByUserIdOrderByAttemptNumberDesc(userId)
                .map(this::mapToKycResponse)
                .orElse(new KycStatusResponse(null, "NOT_STARTED",
                        null, null, null));
    }

    /**
     * Internal: verify user identity for other services.
     * Does NOT return password hash or sensitive fields.
     */
    public IdentitySummaryResponse getIdentitySummary(UUID userId) {
        User user = userRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserNotFoundException(
                        "User not found: " + userId));

        return new IdentitySummaryResponse(
                user.getUserId().toString(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus().name(),
                user.isKycVerified(),
                user.getRoles()
        );
    }

    // ─── Mapping helpers ─────────────────────────────────────

    private UserProfileResponse mapToProfileResponse(User user) {
        return new UserProfileResponse(
                user.getUserId().toString(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getFullName(),
                user.getDateOfBirth().toString(),
                user.getStatus().name(),
                user.getRoles(),
                user.isKycVerified(),
                user.getKycVerifiedAt() != null
                        ? user.getKycVerifiedAt().toString() : null,
                user.getCreatedAt().toString()
        );
    }

    private SessionSummaryResponse mapToSessionResponse(Session session) {
        return new SessionSummaryResponse(
                session.getSessionId().toString(),
                session.getIpAddress(),
                session.getDeviceFingerprint(),
                session.getIssuedAt().toString(),
                session.getLastActivityAt().toString()
        );
    }

    private SessionSummaryResponse mapToSessionSummary(
            Map<String, Object> cached) {
        return new SessionSummaryResponse(
                (String) cached.get("sessionId"),
                (String) cached.get("ipAddress"),
                (String) cached.get("deviceFingerprint"),
                (String) cached.get("issuedAt"),
                (String) cached.get("lastActivityAt")
        );
    }

    private KycStatusResponse mapToKycResponse(KycVerification kyc) {
        return new KycStatusResponse(
                kyc.getVerificationId().toString(),
                kyc.getFinalDecision() != null
                        ? kyc.getFinalDecision().name() : "PENDING",
                kyc.getDocumentType(),
                kyc.getFailureReasons(),
                kyc.getInitiatedAt().toString()
        );
    }
}