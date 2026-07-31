package com.nexus.gateway.jwt;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * JWKS Cache for the Cognito "Option B" auth plane — mirrors JwksCache's
 * structure for the local plane, minus the Redis key-rotation pubsub
 * (Cognito manages its own key rotation; there's no equivalent local
 * notification channel for it, so we rely on the scheduled refresh plus
 * synchronous fetch-if-missing, same as JwksCache's fallback path).
 *
 * Disabled gracefully when nexus.gateway.jwt.cognito.user-pool-id is
 * blank — CompositeJwtValidator never routes to CognitoJwtValidator in
 * that case, so this cache simply never gets queried.
 */
@Slf4j
@Component
public class CognitoJwksCache {

    @Value("${nexus.gateway.jwt.cognito.user-pool-id:}")
    private String userPoolId;

    @Value("${nexus.gateway.jwt.cognito.region:us-east-1}")
    private String region;

    private final ConcurrentHashMap<String, RSAPublicKey> keyRing =
            new ConcurrentHashMap<>();

    private JwkProvider jwkProvider;
    private String jwksUri;

    @PostConstruct
    public void initialize() {
        if (userPoolId.isBlank()) {
            log.info("Cognito user pool not configured — CognitoJwksCache disabled");
            return;
        }

        jwksUri = String.format(
                "https://cognito-idp.%s.amazonaws.com/%s/.well-known/jwks.json",
                region, userPoolId);

        try {
            jwkProvider = new JwkProviderBuilder(URI.create(jwksUri).toURL())
                    .cached(10, 24, TimeUnit.HOURS)
                    .rateLimited(10, 1, TimeUnit.MINUTES)
                    .build();
        } catch (MalformedURLException e) {
            log.error("Invalid Cognito JWKS URI [{}]: {} — Cognito auth disabled",
                    jwksUri, e.getMessage());
            return;
        }

        refreshKeys();
        log.info("Cognito JWKS cache initialized from: {}", jwksUri);
    }

    public boolean isEnabled() {
        return jwkProvider != null;
    }

    public String issuer() {
        return String.format("https://cognito-idp.%s.amazonaws.com/%s",
                region, userPoolId);
    }

    /**
     * Returns the RSA public key for the given kid.
     * Attempts synchronous refresh if kid not found in cache.
     * Returns null if key cannot be found, or the cache is disabled.
     */
    public RSAPublicKey getPublicKey(String kid) {
        if (!isEnabled()) return null;

        RSAPublicKey key = keyRing.get(kid);
        if (key != null) return key;

        log.info("Cognito kid {} not in cache, fetching directly from JWKS", kid);
        try {
            Jwk jwk = jwkProvider.get(kid);
            RSAPublicKey fetched = (RSAPublicKey) jwk.getPublicKey();
            keyRing.put(kid, fetched);
            return fetched;
        } catch (Exception e) {
            log.warn("Could not fetch Cognito key for kid {}: {}", kid, e.getMessage());
            return null;
        }
    }

    @Scheduled(fixedDelayString = "${nexus.gateway.jwt.refresh-interval-minutes:60}",
            timeUnit = TimeUnit.MINUTES)
    public void scheduledRefresh() {
        if (isEnabled()) refreshKeys();
    }

    private void refreshKeys() {
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(jwksUri))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
            String body = http.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body();

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode keys =
                    mapper.readTree(body).path("keys");

            for (com.fasterxml.jackson.databind.JsonNode key : keys) {
                String kid = key.path("kid").asText(null);
                if (kid == null) continue;
                try {
                    Jwk jwk = jwkProvider.get(kid);
                    keyRing.put(kid, (RSAPublicKey) jwk.getPublicKey());
                } catch (Exception e) {
                    log.warn("Failed to cache Cognito key for kid {}: {}", kid, e.getMessage());
                }
            }

            log.info("Cognito JWKS cache refreshed. Keys in ring: {}", keyRing.size());
        } catch (Exception e) {
            log.warn("Cognito JWKS refresh failed: {}", e.getMessage());
        }
    }
}
