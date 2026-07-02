package com.nexus.gateway.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;

/**
 * Redis Configuration — Reactive Lettuce client for rate limiting + JWT blacklist.
 *
 * Lettuce is the non-blocking Redis client that integrates with Project Reactor.
 * All Redis operations return Mono/Flux — no thread blocking.
 *
 * Pool configuration tuned for gateway high-throughput:
 * - 32 max-active / 16 max-idle / 4 min-idle (previously documented as
 *   pooled but the bean never actually used LettucePoolingClientConfiguration
 *   — now it does)
 * - 1000ms command timeout (was 100ms — too aggressive, was failing the
 *   handshake itself under normal Docker networking, not just genuine
 *   Redis unavailability)
 * - TCP keepalive (detect dead connections quickly)
 */
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveStringRedisTemplate(connectionFactory);
    }

    @Bean
    public LettuceClientConfiguration lettuceClientConfiguration() {
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofMillis(500))
                        .keepAlive(SocketOptions.KeepAliveOptions.builder()
                                .enable()
                                .count(3)
                                .idle(Duration.ofSeconds(30))
                                .interval(Duration.ofSeconds(10))
                                .build())
                        .build())
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(1000)))
                .disconnectedBehavior(
                        ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();

        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(4);
        poolConfig.setMaxWait(Duration.ofSeconds(3));

        return LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofMillis(1000))
                .shutdownTimeout(Duration.ofMillis(200))
                .build();
    }
}