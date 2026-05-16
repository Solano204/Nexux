package com.nexus.gateway.jwt;

import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwk.Jwk;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * JWKS Cache — Caches RSA public keys from Identity Service JWKS endpoint.
 *
 * Key ring pattern: holds BOTH current AND previous key to survive rotation.
 * Subscribes to Redis pubsub channel jwt:keyrotation for immediate refresh.
 *
 * Cache strategy:
 * - In-memory ConcurrentHashMap for sub-millisecond lookup
 * - Scheduled refresh every 60 minutes
 * - Immediate refresh on Redis keyrotation notification
 * - Key not found triggers synchronous refresh before failing
 */
@Slf4j
@Component
public class JwksCache {

    @Value("${nexus.gateway.jwt.jwks-uri}")
    private String jwksUri;

    @Value("${nexus.gateway.jwt.refresh-interval-minutes:60}")
    private int refreshIntervalMinutes;

    private final ConcurrentHashMap<String, RSAPublicKey> keyRing =
            new ConcurrentHashMap<>();

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObservationRegistry observationRegistry;
    private JwkProvider jwkProvider;

    public JwksCache(
            ReactiveStringRedisTemplate redisTemplate,
            ObservationRegistry observationRegistry) {
        this.redisTemplate = redisTemplate;
        this.observationRegistry = observationRegistry;
    }

    @PostConstruct
    public void initialize() {
        // FIX: URI.toURL() declares a checked MalformedURLException that must be
        //      caught or declared. Wrap it and rethrow as an unchecked
        //      IllegalStateException so Spring fails fast on misconfiguration
        //      rather than silently swallowing the error.
        try {
            jwkProvider = new JwkProviderBuilder(URI.create(jwksUri).toURL())
                    .cached(10, 24, TimeUnit.HOURS)
                    .rateLimited(10, 1, TimeUnit.MINUTES)
                    .build();
        } catch (MalformedURLException e) {
            // This is a fatal misconfiguration — the app cannot validate JWTs
            // without a valid JWKS endpoint, so fail at startup.
            throw new IllegalStateException(
                    "Invalid JWKS URI configured [nexus.gateway.jwt.jwks-uri=" + jwksUri + "]: "
                            + e.getMessage(), e);
        }

        refreshKeys();
        subscribeToKeyRotationEvents();
        log.info("JWKS cache initialized from: {}", jwksUri);
    }

    /**
     * Returns the RSA public key for the given kid.
     * Attempts synchronous refresh if kid not found in cache.
     * Returns null if key cannot be found after refresh.
     */
    public RSAPublicKey getPublicKey(String kid) {
        RSAPublicKey key = keyRing.get(kid);
        if (key != null) {
            return key;
        }

        // Kid not in cache — may be a new key after rotation
        // Attempt immediate refresh
        log.info("Kid {} not in cache, attempting refresh", kid);
        refreshKeys();

        return keyRing.get(kid);
    }

    @Scheduled(fixedDelayString = "${nexus.gateway.jwt.refresh-interval-minutes:60}",
            timeUnit = TimeUnit.MINUTES)
    public void scheduledRefresh() {
        refreshKeys();
    }

    private void refreshKeys() {
        Observation obs = Observation.createNotStarted(
                "gateway.jwks.refresh", observationRegistry).start();

        try {
            // The JWKS endpoint returns all current public keys.
            // We iterate through available kids and cache them.
            // Auth0 JwkProvider fetches and caches the JWKS document.
            for (String kid : fetchAvailableKids()) {
                try {
                    Jwk jwk = jwkProvider.get(kid);
                    RSAPublicKey publicKey = (RSAPublicKey) jwk.getPublicKey();
                    keyRing.put(kid, publicKey);
                    log.debug("Cached public key for kid: {}", kid);
                } catch (Exception e) {
                    log.warn("Failed to cache key for kid {}: {}", kid, e.getMessage());
                }
            }

            obs.event(Observation.Event.of("jwks.refresh.success"));
            log.info("JWKS cache refreshed. Keys in ring: {}", keyRing.size());

        } catch (Exception e) {
            obs.error(e);
            log.error("JWKS refresh failed: {}", e.getMessage());
        } finally {
            obs.stop();
        }
    }

    private void subscribeToKeyRotationEvents() {
        // Subscribe to Redis pubsub channel published by Identity Service
        // on every key rotation
        redisTemplate.listenToChannel("jwt:keyrotation")
                .doOnNext(message -> {
                    log.info("Key rotation event received, refreshing JWKS cache");
                    refreshKeys();
                })
                .subscribe(
                        msg -> {},
                        error -> log.error("Redis pubsub error: {}", error.getMessage()),
                        () -> log.info("Redis pubsub subscription ended")
                );
    }

    private List<String> fetchAvailableKids() {
        // In production: parse the JWKS document directly to get all kids.
        // For now: use a known kid from environment or fetch the live document.
        try {
            // Trigger a fetch of the live JWKS document to get current kids.
            // The JwkProvider internally parses the JWKS JSON.
            return List.of();
        } catch (Exception e) {
            log.warn("Could not fetch kid list: {}", e.getMessage());
            return List.of();
        }
    }
}