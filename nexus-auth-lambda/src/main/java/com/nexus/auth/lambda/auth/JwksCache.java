package com.nexus.auth.lambda.auth;

import com.auth0.jwk.*;
import com.auth0.jwt.interfaces.RSAKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JWKS Cache — Cognito public key management.
 *
 * Initialized ONCE at class load → captured in SnapStart snapshot.
 * Keys are pre-fetched and cached in the snapshot.
 *
 * refreshIfStale() called in CRaC afterRestore():
 * - If snapshot is old (>1hr), Cognito may have rotated keys
 * - Refresh ensures new tokens signed with new keys are accepted
 *
 * getPublicKey(kid) — O(1) lookup from in-memory cache.
 * On cache miss: attempt refresh before returning empty.
 */
public class JwksCache implements RSAKeyProvider {

    private static final Logger log =
            LoggerFactory.getLogger(JwksCache.class);

    private static final long STALE_THRESHOLD_MS = 60 * 60 * 1000L;

    private final String jwksUrl;
    private final AtomicReference<JwkProvider> providerRef;
    private volatile Instant lastRefreshedAt;

    private JwksCache(String jwksUrl) {
        this.jwksUrl = jwksUrl;
        this.providerRef = new AtomicReference<>();
        this.lastRefreshedAt = Instant.EPOCH;
    }

    public static JwksCache initialize(String jwksUrl) {
        JwksCache cache = new JwksCache(jwksUrl);
        cache.refresh();
        return cache;
    }

    public Optional<RSAPublicKey> getPublicKey(String kid) {
        try {
            JwkProvider provider = providerRef.get();
            if (provider == null) return Optional.empty();

            Jwk jwk = provider.get(kid);
            return Optional.of((RSAPublicKey) jwk.getPublicKey());

        } catch (SigningKeyNotFoundException e) {
            // Key not in cache — try refresh once
            log.warn("Unknown kid={}, attempting JWKS refresh", kid);
            refresh();
            try {
                Jwk jwk = providerRef.get().get(kid);
                return Optional.of((RSAPublicKey) jwk.getPublicKey());
            } catch (Exception e2) {
                log.error("Key not found after refresh: kid={}", kid);
                return Optional.empty();
            }
        } catch (Exception e) {
            log.error("JWKS error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void refreshIfStale() {
        long ageMs = Instant.now().toEpochMilli() -
                lastRefreshedAt.toEpochMilli();
        if (ageMs > STALE_THRESHOLD_MS) {
            log.info("JWKS stale after SnapStart restore " +
                    "(age={}ms), refreshing", ageMs);
            refresh();
        }
    }

    private synchronized void refresh() {
        try {
            JwkProvider provider = new JwkProviderBuilder(
                    new URL(jwksUrl))
                    .cached(true)
                    .rateLimited(false)
                    .build();

            providerRef.set(provider);
            lastRefreshedAt = Instant.now();
            log.info("JWKS refreshed from: {}", jwksUrl);

        } catch (Exception e) {
            log.error("JWKS refresh failed: {}", e.getMessage(), e);
        }
    }

    // RSAKeyProvider implementation (for Auth0 JWT)
    @Override
    public RSAPublicKey getPublicKeyById(String kid) {
        return getPublicKey(kid).orElse(null);
    }

    @Override
    public RSAPrivateKey getPrivateKey() { return null; }

    @Override
    public String getPrivateKeyId() { return null; }
}