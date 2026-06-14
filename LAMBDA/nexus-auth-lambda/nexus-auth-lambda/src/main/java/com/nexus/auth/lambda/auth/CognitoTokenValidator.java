package com.nexus.auth.lambda.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Optional;

/**
 * Cognito Token Validator — encapsulates Cognito JWT validation logic.
 *
 * Validation steps:
 * 1. Decode JWT header (unauthenticated) to extract kid
 * 2. Fetch RSA public key from JwksCache by kid
 * 3. Verify RS256 signature using the public key
 * 4. Validate claims:
 *    - iss: must match Cognito User Pool URL
 *    - client_id: must match the registered app client
 *    - token_use: must be "access" (not "id")
 *    - exp: must not be expired
 *
 * Thread-safe: all state is in the JwksCache (AtomicReference).
 * Used by TokenValidationHandler and TokenRefreshHandler.
 *
 * SnapStart-safe: JwksCache is pre-loaded in the static initializer
 * and refreshed in afterRestore() — no stale key issues.
 */
public class CognitoTokenValidator {

    private static final Logger log =
        LoggerFactory.getLogger(CognitoTokenValidator.class);

    private final JwksCache jwksCache;
    private final String issuer;
    private final String clientId;

    /**
     * @param jwksCache  Pre-loaded JWKS cache (from SnapStart init)
     * @param region     AWS region (e.g. "us-east-1")
     * @param userPoolId Cognito User Pool ID
     * @param clientId   Cognito App Client ID
     */
    public CognitoTokenValidator(JwksCache jwksCache,
                                   String region,
                                   String userPoolId,
                                   String clientId) {
        this.jwksCache = jwksCache;
        this.issuer = String.format(
            "https://cognito-idp.%s.amazonaws.com/%s",
            region, userPoolId);
        this.clientId = clientId;
    }

    /**
     * Validates a Cognito access token.
     *
     * @param token Raw JWT string (without "Bearer " prefix)
     * @return ValidationResult with decoded JWT or error details
     */
    public ValidationResult validate(String token) {
        if (token == null || token.isBlank()) {
            return ValidationResult.error("MISSING_TOKEN",
                "Token is required");
        }

        // Step 1: Decode header to extract kid
        DecodedJWT unverified;
        try {
            unverified = JWT.decode(token);
        } catch (JWTDecodeException e) {
            return ValidationResult.error("MALFORMED_TOKEN",
                "Token format is invalid");
        }

        String kid = unverified.getKeyId();
        if (kid == null || kid.isBlank()) {
            return ValidationResult.error("MISSING_KID",
                "Token missing key ID header");
        }

        // Step 2: Get public key from JWKS cache
        Optional<RSAPublicKey> publicKey = jwksCache.getPublicKey(kid);
        if (publicKey.isEmpty()) {
            log.warn("Unknown signing key: kid={}", kid);
            return ValidationResult.error("UNKNOWN_KEY",
                "Token signed with unknown key");
        }

        // Step 3: Verify signature + claims
        try {
            Algorithm algorithm = Algorithm.RSA256(
                publicKey.get(), null);

            JWTVerifier verifier = JWT.require(algorithm)
                .withIssuer(issuer)
                .withClaim("client_id", clientId)
                .withClaim("token_use", "access")
                .build();

            DecodedJWT verified = verifier.verify(token);

            return ValidationResult.valid(verified);

        } catch (TokenExpiredException e) {
            return ValidationResult.error("TOKEN_EXPIRED",
                "Token has expired");
        } catch (JWTVerificationException e) {
            log.warn("JWT verification failed: {}", e.getMessage());
            return ValidationResult.error("INVALID_TOKEN",
                "Token verification failed: " + e.getMessage());
        }
    }

    /**
     * Quick decode without full verification — used for extracting
     * claims from a token that has already been validated by Cognito
     * (e.g. a freshly issued token from REFRESH_TOKEN_AUTH flow).
     */
    public Optional<DecodedJWT> decodeWithoutVerification(String token) {
        try {
            return Optional.of(JWT.decode(token));
        } catch (JWTDecodeException e) {
            log.warn("Failed to decode token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Validation result — either valid with decoded JWT,
     * or error with code + message.
     */
    public record ValidationResult(
        boolean valid,
        DecodedJWT decodedJwt,
        String errorCode,
        String errorMessage
    ) {
        public static ValidationResult valid(DecodedJWT jwt) {
            return new ValidationResult(true, jwt, null, null);
        }

        public static ValidationResult error(String code, String msg) {
            return new ValidationResult(false, null, code, msg);
        }

        public String subject() {
            return decodedJwt != null ? decodedJwt.getSubject() : null;
        }

        public String jti() {
            return decodedJwt != null ? decodedJwt.getId() : null;
        }

        public Instant expiresAt() {
            return decodedJwt != null
                ? decodedJwt.getExpiresAtAsInstant() : null;
        }
    }
}
