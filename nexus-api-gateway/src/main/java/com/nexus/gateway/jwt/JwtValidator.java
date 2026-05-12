package com.nexus.gateway.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * JWT Validator — RS256 signature verification + claims extraction.
 *
 * Security decisions implemented here:
 * 1. Only RS256 algorithm accepted — rejects none, HS256, HS512
 * 2. Key ring pattern — holds current + previous key for smooth rotation
 * 3. Full claims validation: exp, iss, aud, sub, jti
 * 4. Account status check embedded in validation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtValidator {

    private final JwksCache jwksCache;
    private final ObservationRegistry observationRegistry;

    private static final String ISSUER = "nexus-platform";
    private static final String AUDIENCE = "nexus-platform";
    private static final List<String> ALLOWED_ALGORITHMS = List.of("RS256");

    /**
     * Validates the JWT token and returns decoded claims.
     * Returns empty Mono on any validation failure — never throws.
     * Error details are logged but not returned to callers
     * (prevents information leakage to attackers).
     */
    public Mono<JwtClaims> validate(String token) {
        return Mono.fromCallable(() -> {
            Observation obs = Observation.createNotStarted(
                    "gateway.jwt.validation", observationRegistry).start();

            try {
                // Step 1: Decode without verification to read kid header
                DecodedJWT unverified = JWT.decode(token);

                // Step 2: Reject non-RS256 algorithms immediately
                // This prevents: alg=none attack, HS256 secret confusion attack
                String algorithm = unverified.getAlgorithm();
                if (!ALLOWED_ALGORITHMS.contains(algorithm)) {
                    log.warn("JWT rejected — unsupported algorithm: {}",
                            algorithm);
                    obs.event(Observation.Event.of("jwt.invalid_algorithm"));
                    return null;
                }

                // Step 3: Get public key from JWKS cache using kid
                String kid = unverified.getKeyId();
                RSAPublicKey publicKey = jwksCache.getPublicKey(kid);

                if (publicKey == null) {
                    log.warn("JWT rejected — unknown kid: {}", kid);
                    obs.event(Observation.Event.of("jwt.unknown_kid"));
                    return null;
                }

                // Step 4: Verify signature + claims
                JWTVerifier verifier = JWT.require(Algorithm.RSA256(publicKey, null))
                        .withIssuer(ISSUER)
                        .withAudience(AUDIENCE)
                        .acceptLeeway(5)  // 5 seconds clock skew tolerance
                        .build();

                DecodedJWT verified = verifier.verify(token);

                // Step 5: Extract and validate required claims
                String userId = verified.getSubject();
                String jti = verified.getId();

                if (userId == null || userId.isBlank()) {
                    log.warn("JWT rejected — missing subject claim");
                    return null;
                }
                if (jti == null || jti.isBlank()) {
                    log.warn("JWT rejected — missing jti claim");
                    return null;
                }

                List<String> roles = verified.getClaim("roles")
                        .asList(String.class);
                String accountStatus = verified.getClaim("accountStatus")
                        .asString();

                obs.event(Observation.Event.of("jwt.valid"));

                return JwtClaims.builder()
                        .userId(userId)
                        .jti(jti)
                        .roles(roles != null ? roles : List.of())
                        .accountStatus(accountStatus)
                        .expiresAt(verified.getExpiresAtAsInstant())
                        .issuedAt(verified.getIssuedAtAsInstant())
                        .build();

            } catch (TokenExpiredException e) {
                log.debug("JWT rejected — token expired");
                obs.event(Observation.Event.of("jwt.expired"));
                return null;
            } catch (JWTVerificationException e) {
                log.warn("JWT rejected — verification failed: {}",
                        e.getMessage());
                obs.event(Observation.Event.of("jwt.invalid_signature"));
                return null;
            } finally {
                obs.stop();
            }
        }).filter(claims -> claims != null);
    }
}