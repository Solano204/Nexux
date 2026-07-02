package com.nexus.fraud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis Configuration — Fraud Service.
 *
 * Three distinct Redis uses:
 * 1. fraud:merchant:blacklist (SET) — O(1) merchant blacklist
 * 2. fraud:account:flagged (SET) — flagged accounts
 * 3. fraud:velocity:{userId} (STRING, 5s TTL) — velocity cache
 * 4. fraud:user:recent:{userId} (LIST) — last 10 decisions
 * 5. user:locations:{userId} (LIST) — geolocation history (read-only, written by identity-service)
 * 6. user:behavioral:{userId} (STRING) — behavioral profile (read-only, written by risk-scoring-service)
 *
 * Connection timeout: 1000ms (was 100ms — too aggressive, was failing the
 *   handshake itself under normal Docker networking, not just genuine
 *   Redis unavailability). Fraud analysis is still latency-sensitive, this
 *   just gives real headroom instead of failing on jitter.
 * Lettuce with connection pooling (16 max-active / 8 max-idle / 2 min-idle)
 * for concurrent Virtual Thread access — was actually unpooled before.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:nexus-redis}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);
        if (redisPassword != null && !redisPassword.isBlank()) {
            config.setPassword(redisPassword);
        }

        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setMaxWait(Duration.ofSeconds(3));

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .commandTimeout(Duration.ofMillis(1000))
                .build();

        return new LettuceConnectionFactory(config, clientConfig);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}