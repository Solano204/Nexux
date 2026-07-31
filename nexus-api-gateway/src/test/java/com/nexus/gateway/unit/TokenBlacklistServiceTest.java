package com.nexus.gateway.unit;

import com.nexus.gateway.jwt.TokenBlacklistService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class TokenBlacklistServiceTest {

    @Mock
    ReactiveStringRedisTemplate redisTemplate;

    @Mock
    ReactiveValueOperations<String, String> valueOps;

    // ObservationRegistry.NOOP, not a @Mock - isBlacklisted() calls real
    // Observation.createNotStarted() unconditionally, which NPEs against
    // an unstubbed Mockito mock (observationConfig() returns null).
    final ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

    TokenBlacklistService blacklistService;

    @BeforeEach
    void setUp() throws Exception {
        // Not @InjectMocks: keyPrefix/checkTimeoutMs are @Value-injected
        // fields, which only Spring's context populates - @InjectMocks
        // just calls the constructor and leaves them at Java defaults
        // (null / 0), which would silently break every test here (wrong
        // Redis key via "null" + jti, and a 0ms timeout that always fires).
        blacklistService = new TokenBlacklistService(redisTemplate, observationRegistry);
        setField(blacklistService, "keyPrefix", "jwt:blacklist:");
        setField(blacklistService, "checkTimeoutMs", 100L);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("isBlacklisted returns true when token is in Redis")
    void isBlacklisted_tokenInRedis_returnsTrue() {
        when(redisTemplate.hasKey("jwt:blacklist:test-jti-123"))
                .thenReturn(Mono.just(true));

        StepVerifier.create(blacklistService.isBlacklisted("test-jti-123"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("isBlacklisted returns false when token not in Redis")
    void isBlacklisted_tokenNotInRedis_returnsFalse() {
        when(redisTemplate.hasKey("jwt:blacklist:test-jti-456"))
                .thenReturn(Mono.just(false));

        StepVerifier.create(blacklistService.isBlacklisted("test-jti-456"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("isBlacklisted returns false when Redis is unavailable (graceful degradation)")
    void isBlacklisted_redisUnavailable_returnsFalseAndLogs() {
        when(redisTemplate.hasKey(anyString()))
                .thenReturn(Mono.error(
                        new RuntimeException("Redis connection refused")));

        // Security trade-off: availability over absolute revocation during outage
        StepVerifier.create(blacklistService.isBlacklisted("any-jti"))
                .expectNext(false)  // Allow through — not false-reject users
                .verifyComplete();
    }

    @Test
    @DisplayName("isBlacklisted times out at 100ms and falls back to false")
    void isBlacklisted_redisTimeout_returnsFalseWithinBound() {
        // Simulate Redis never responding
        when(redisTemplate.hasKey(anyString()))
                .thenReturn(Mono.never());

        StepVerifier.withVirtualTime(
                        () -> blacklistService.isBlacklisted("jti-timeout-test"))
                .thenAwait(Duration.ofMillis(150))
                .expectNext(false)
                .verifyComplete();
    }
}
