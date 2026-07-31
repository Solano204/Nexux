package com.nexus.gateway.unit;

import com.nexus.gateway.featureflag.FeatureFlagService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    @Mock private ReactiveStringRedisTemplate redisTemplate;
    @Mock private ReactiveValueOperations<String, String> valueOperations;

    private FeatureFlagService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new FeatureFlagService(redisTemplate, meterRegistry);
    }

    @Test
    void isEnabledReturnsTrueWhenFlagAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("feature-flag:ai-assistant")).thenReturn(Mono.empty());

        StepVerifier.create(service.isEnabled("ai-assistant"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void isEnabledReturnsFalseWhenExplicitlyDisabled() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("feature-flag:ai-assistant")).thenReturn(Mono.just("disabled"));

        StepVerifier.create(service.isEnabled("ai-assistant"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void isEnabledReturnsTrueForAnyOtherValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("feature-flag:ai-assistant")).thenReturn(Mono.just("enabled"));

        StepVerifier.create(service.isEnabled("ai-assistant"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void isEnabledFailsOpenOnRedisError() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(Mono.error(new RuntimeException("redis down")));

        StepVerifier.create(service.isEnabled("ai-assistant"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void disableSetsFlagAndReasonAndIncrementsCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.set(eq("feature-flag:ai-assistant"), eq("disabled"))).thenReturn(Mono.just(true));
        when(valueOperations.set(eq("feature-flag:ai-assistant:reason"), anyString())).thenReturn(Mono.just(true));

        StepVerifier.create(service.disable("ai-assistant", "OpenAI outage")).verifyComplete();

        assertThat(meterRegistry.counter("feature.flag.disabled.total", "feature", "ai-assistant").count())
                .isEqualTo(1.0);
    }

    @Test
    void enableDeletesFlagAndReasonKeys() {
        when(redisTemplate.delete("feature-flag:ai-assistant")).thenReturn(Mono.just(1L));
        when(redisTemplate.delete("feature-flag:ai-assistant:reason")).thenReturn(Mono.just(1L));

        StepVerifier.create(service.enable("ai-assistant")).verifyComplete();
    }

    @Test
    void disabledReasonDefaultsToUnknown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("feature-flag:ai-assistant:reason")).thenReturn(Mono.empty());

        StepVerifier.create(service.disabledReason("ai-assistant"))
                .expectNext("unknown")
                .verifyComplete();
    }
}
