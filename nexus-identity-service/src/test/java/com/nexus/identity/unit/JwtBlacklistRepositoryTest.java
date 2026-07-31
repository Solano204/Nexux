package com.nexus.identity.unit;

import com.nexus.identity.infrastructure.redis.JwtBlacklistRepository;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtBlacklistRepositoryTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private JwtBlacklistRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JwtBlacklistRepository(redisTemplate, ObservationRegistry.NOOP);
    }

    @Test
    void blacklistSetsKeyWithRemainingTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        repository.blacklist("jti-1", Instant.now().plusSeconds(900));

        verify(valueOperations).setIfAbsent(eq("jwt:blacklist:jti-1"), eq("1"), any(Duration.class));
    }

    @Test
    void blacklistSkipsAlreadyExpiredTokens() {
        repository.blacklist("jti-1", Instant.now().minusSeconds(10));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void blacklistSwallowsRedisFailures() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThatCodeDoesNotThrow(() -> repository.blacklist("jti-1", Instant.now().plusSeconds(900)));
    }

    @Test
    void isBlacklistedReturnsTrueWhenKeyExists() {
        when(redisTemplate.hasKey("jwt:blacklist:jti-1")).thenReturn(true);

        assertThat(repository.isBlacklisted("jti-1")).isTrue();
    }

    @Test
    void isBlacklistedReturnsFalseWhenKeyMissing() {
        when(redisTemplate.hasKey("jwt:blacklist:jti-1")).thenReturn(false);

        assertThat(repository.isBlacklisted("jti-1")).isFalse();
    }

    @Test
    void isBlacklistedFailsOpenOnRedisOutage() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(repository.isBlacklisted("jti-1")).isFalse();
    }

    @Test
    void publishRevocationEventSwallowsFailures() {
        doThrow(new RuntimeException("redis down"))
                .when(redisTemplate).convertAndSend(anyString(), any());

        assertThatCodeDoesNotThrow(() -> repository.publishRevocationEvent("jti-1"));
    }

    private void assertThatCodeDoesNotThrow(Runnable r) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(r::run);
    }
}
