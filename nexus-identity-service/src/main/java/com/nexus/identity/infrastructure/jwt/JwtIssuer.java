package com.nexus.identity.infrastructure.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.nexus.identity.domain.model.User;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT Issuer — Sole authority for creating JWTs in the platform.
 *
 * Only this service can create valid JWTs because only this service
 * holds the RS256 private key. The gateway and all downstream
 * services only have the public key for verification.
 *
 * Token structure:
 * - sub: userId
 * - iss: nexus-identity-service
 * - aud: nexus-platform
 * - jti: unique UUID per token (for blacklist)
 * - kid: key ID (for rotation support)
 * - roles, accountStatus, kycVerified in custom claims
 */
@Slf4j
@Component
public class JwtIssuer {

    private final JwtKeyManager keyManager;
    private final Counter jwtIssuedCounter;
    private final Counter jwtRefreshedCounter;

    @Value("${nexus.jwt.access-token-expiry-seconds:900}")
    private long accessTokenExpirySeconds;

    @Value("${nexus.jwt.issuer:nexus-identity-service}")
    private String issuer;

    @Value("${nexus.jwt.audience:nexus-platform}")
    private String audience;

    public JwtIssuer(JwtKeyManager keyManager, MeterRegistry registry) {
        this.keyManager = keyManager;
        this.jwtIssuedCounter = Counter.builder("identity.jwt.issued")
                .description("Total JWTs issued")
                .register(registry);
        this.jwtRefreshedCounter = Counter.builder("identity.jwt.refreshed")
                .description("Total JWTs refreshed")
                .register(registry);
    }

    /**
     * Issues a new access token for the given user.
     * RS256 signed with current private key.
     */
    public TokenPair issueTokens(User user, String deviceFingerprint) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();

        Algorithm algorithm = Algorithm.RSA256(
                null, keyManager.getCurrentPrivateKey());

        String accessToken = JWT.create()
                .withSubject(user.getUserId().toString())
                .withIssuer(issuer)
                .withAudience(audience)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plusSeconds(accessTokenExpirySeconds)))
                .withJWTId(jti)
                .withKeyId(keyManager.getCurrentKid())
                .withClaim("email", user.getEmail())
                .withClaim("roles", user.getRoles())
                .withClaim("accountStatus", user.getStatus().name())
                .withClaim("kycVerified", user.isKycVerified())
                .sign(algorithm);

        // Refresh token: 256 bits cryptographically random
        String refreshToken = generateSecureRefreshToken();

        jwtIssuedCounter.increment();

        log.debug("JWT issued: userId={} jti={} kid={}",
                user.getUserId(), jti, keyManager.getCurrentKid());

        return new TokenPair(
                accessToken,
                refreshToken,
                jti,
                now,
                now.plusSeconds(accessTokenExpirySeconds)
        );
    }

    /**
     * Refreshes an access token (same user, new jti).
     */
    public String refreshAccessToken(User user, String oldJti) {
        Instant now = Instant.now();
        String newJti = UUID.randomUUID().toString();

        Algorithm algorithm = Algorithm.RSA256(
                null, keyManager.getCurrentPrivateKey());

        String newToken = JWT.create()
                .withSubject(user.getUserId().toString())
                .withIssuer(issuer)
                .withAudience(audience)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plusSeconds(accessTokenExpirySeconds)))
                .withJWTId(newJti)
                .withKeyId(keyManager.getCurrentKid())
                .withClaim("email", user.getEmail())
                .withClaim("roles", user.getRoles())
                .withClaim("accountStatus", user.getStatus().name())
                .withClaim("kycVerified", user.isKycVerified())
                .sign(algorithm);

        jwtRefreshedCounter.increment();

        log.debug("JWT refreshed: userId={} oldJti={} newJti={}",
                user.getUserId(), oldJti, newJti);

        return newToken;
    }

    private String generateSecureRefreshToken() {
        byte[] bytes = new byte[32]; // 256 bits
        java.security.SecureRandom.getInstanceStrong()
                .nextBytes(bytes);
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    public record TokenPair(
            String accessToken,
            String refreshToken,
            String jti,
            Instant issuedAt,
            Instant expiresAt
    ) {}
}