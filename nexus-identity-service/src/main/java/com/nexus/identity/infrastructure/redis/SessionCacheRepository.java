package com.nexus.identity.infrastructure.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Session Cache Repository — Redis-backed caches for the identity service.
 *
 * Key namespaces:
 *   session:{userId}:active   → cached active session list (5-min TTL)
 *   auth:failed:{email}       → failed login attempt counter (15-min window)
 *   kyc:retries:{userId}      → KYC attempt counter (30-day window)
 *   pwreset:{token}           → password reset token → userId (30-min TTL)
 *
 * All methods are best-effort: Redis failures are logged but never
 * propagate to callers (the DB is the source of truth).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SessionCacheRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String SESSION_KEY_PREFIX  = "session:";
    private static final String SESSION_KEY_SUFFIX  = ":active";
    private static final String FAILED_KEY_PREFIX   = "auth:failed:";
    private static final String KYC_RETRY_PREFIX    = "kyc:retries:";
    private static final String PW_RESET_PREFIX     = "pwreset:";

    private static final Duration SESSION_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration FAILED_WINDOW_TTL = Duration.ofMinutes(15);
    private static final Duration KYC_WINDOW_TTL    = Duration.ofDays(30);

    // ── Active session list cache ─────────────────────────────

    public void cacheActiveSessions(UUID userId,
                                    List<Map<String, Object>> sessions) {
        String key = buildSessionKey(userId);
        try {
            String json = objectMapper.writeValueAsString(sessions);
            redisTemplate.opsForValue().set(key, json, SESSION_CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache sessions: userId={} error={}",
                    userId, e.getMessage());
        }
    }

    public List<Map<String, Object>> getCachedSessions(UUID userId) {
        String key = buildSessionKey(userId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to read session cache: userId={} error={}",
                    userId, e.getMessage());
            return null;
        }
    }

    public void invalidate(UUID userId) {
        try {
            redisTemplate.delete(buildSessionKey(userId));
        } catch (Exception e) {
            log.warn("Failed to invalidate session cache: userId={}",
                    userId);
        }
    }

    // ── Failed login tracking ─────────────────────────────────

    public void incrementFailedAttempts(String email) {
        String key = FAILED_KEY_PREFIX + email.toLowerCase();
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, FAILED_WINDOW_TTL);
            }
        } catch (Exception e) {
            log.warn("Failed to increment login attempts: email={}",
                    maskEmail(email));
        }
    }

    public int getFailedAttempts(String email) {
        String key = FAILED_KEY_PREFIX + email.toLowerCase();
        try {
            String val = redisTemplate.opsForValue().get(key);
            return val == null ? 0 : Integer.parseInt(val);
        } catch (Exception e) {
            log.warn("Failed to read login attempts: email={}",
                    maskEmail(email));
            return 0;
        }
    }

    public void resetFailedAttempts(String email) {
        try {
            redisTemplate.delete(
                    FAILED_KEY_PREFIX + email.toLowerCase());
        } catch (Exception e) {
            log.warn("Failed to reset login attempts: email={}",
                    maskEmail(email));
        }
    }

    // ── KYC retry counter ─────────────────────────────────────

    public int getKycRetryCount(UUID userId) {
        String key = KYC_RETRY_PREFIX + userId;
        try {
            String val = redisTemplate.opsForValue().get(key);
            return val == null ? 0 : Integer.parseInt(val);
        } catch (Exception e) {
            log.warn("Failed to read KYC retry count: userId={}", userId);
            return 0;
        }
    }

    public void incrementKycRetryCount(UUID userId) {
        String key = KYC_RETRY_PREFIX + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, KYC_WINDOW_TTL);
            }
        } catch (Exception e) {
            log.warn("Failed to increment KYC retry count: userId={}", userId);
        }
    }

    // ── Password reset tokens ─────────────────────────────────

    /**
     * Store a password reset token in Redis.
     *
     * Key:   pwreset:{token}
     * Value: userId.toString()
     * TTL:   configurable (30 minutes by default)
     *
     * The token is one-time: deletePasswordResetToken() is called
     * immediately after confirmPasswordReset() succeeds.
     *
     * @param token     URL-safe base64 token (256-bit random)
     * @param userId    owner of the reset request
     * @param ttl       how long the token is valid
     */
    public void storePasswordResetToken(String token, UUID userId,
                                        Duration ttl) {
        String key = PW_RESET_PREFIX + token;
        try {
            redisTemplate.opsForValue().set(key, userId.toString(), ttl);
            log.debug("Password reset token stored: userId={} ttl={}",
                    userId, ttl);
        } catch (Exception e) {
            log.error("Failed to store password reset token: userId={} {}",
                    userId, e.getMessage());
            throw new RuntimeException(
                    "Failed to store password reset token", e);
        }
    }

    /**
     * Resolve a password reset token to its owner userId.
     *
     * Returns Optional.empty() if:
     *   - Token does not exist (never issued or already used)
     *   - Token has expired (Redis TTL elapsed)
     *   - Redis is unavailable
     *
     * Does NOT delete the token — call deletePasswordResetToken()
     * after successfully completing the reset.
     *
     * @param token the raw token from the reset URL query parameter
     * @return Optional<UUID> the userId if token is valid
     */
    public Optional<UUID> resolvePasswordResetToken(String token) {
        String key = PW_RESET_PREFIX + token;
        try {
            String userId = redisTemplate.opsForValue().get(key);
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(userId));
        } catch (Exception e) {
            log.warn("Failed to resolve password reset token: {}",
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Delete a password reset token (enforce one-time use).
     * Called immediately after confirmPasswordReset() succeeds.
     *
     * @param token the token to invalidate
     */
    public void deletePasswordResetToken(String token) {
        String key = PW_RESET_PREFIX + token;
        try {
            redisTemplate.delete(key);
            log.debug("Password reset token deleted (one-time use enforced)");
        } catch (Exception e) {
            log.warn("Failed to delete password reset token: {}",
                    e.getMessage());
            // Non-fatal: token TTL will expire it naturally in 30 min
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private String buildSessionKey(UUID userId) {
        return SESSION_KEY_PREFIX + userId + SESSION_KEY_SUFFIX;
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        String[] parts = email.split("@");
        return (parts[0].length() > 1
                ? parts[0].charAt(0) + "***" : "***")
                + "@" + parts[1];
    }
}