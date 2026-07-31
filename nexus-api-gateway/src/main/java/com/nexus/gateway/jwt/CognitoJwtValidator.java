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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Cognito JWT Validator — "Option B" from
 * AWS-DOCKER-WORKFLOWS/02_LOGIN_FLOW.md. Validates Cognito **ID tokens**
 * (not access tokens) issued to users mirrored by CognitoUserMirror in
 * nexus-identity-service.
 *
 * ID token, not access token, deliberately: access tokens don't carry
 * custom attributes by default (would need a Resource Server + custom
 * scopes, or a live GetUser call per request). ID tokens include
 * custom:userId / custom:accountStatus / custom:kycVerified directly as
 * claims — zero extra AWS calls in the gateway's hot path, same latency
 * profile as the local JWT path.
 *
 * userId comes from the custom:userId claim (the mirrored Postgres UUID),
 * NOT from `sub` — Cognito's sub is a separate, Cognito-generated
 * identifier that downstream services (which key everything off the
 * Postgres UUID) don't know about.
 *
 * Roles are not carried through Cognito today — every validated Cognito
 * token gets a hardcoded ["USER"] role set. Known gap, not addressed here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CognitoJwtValidator implements JwtValidator {

    private final CognitoJwksCache jwksCache;
    private final ObservationRegistry observationRegistry;

    @Value("${nexus.gateway.jwt.cognito.client-id:}")
    private String clientId;

    private static final List<String> ALLOWED_ALGORITHMS = List.of("RS256");

    @Override
    public Mono<JwtClaims> validate(String token) {
        if (!jwksCache.isEnabled()) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            Observation obs = Observation.createNotStarted(
                    "gateway.jwt.cognito.validation", observationRegistry).start();

            try {
                DecodedJWT unverified = JWT.decode(token);

                String algorithm = unverified.getAlgorithm();
                if (!ALLOWED_ALGORITHMS.contains(algorithm)) {
                    log.warn("Cognito JWT rejected — unsupported algorithm: {}", algorithm);
                    return null;
                }

                String kid = unverified.getKeyId();
                RSAPublicKey publicKey = jwksCache.getPublicKey(kid);
                if (publicKey == null) {
                    log.warn("Cognito JWT rejected — unknown kid: {}", kid);
                    return null;
                }

                JWTVerifier verifier = JWT.require(Algorithm.RSA256(publicKey, null))
                        .withIssuer(jwksCache.issuer())
                        .withAudience(clientId)
                        .withClaim("token_use", "id")
                        .acceptLeeway(5)
                        .build();

                DecodedJWT verified = verifier.verify(token);

                String userId = verified.getClaim("custom:userId").asString();
                String jti = verified.getId();

                if (userId == null || userId.isBlank()) {
                    log.warn("Cognito JWT rejected — missing custom:userId claim " +
                            "(user not mirrored, or attribute not readable on this app client)");
                    return null;
                }
                if (jti == null || jti.isBlank()) {
                    log.warn("Cognito JWT rejected — missing jti claim");
                    return null;
                }

                String accountStatus = verified.getClaim("custom:accountStatus").asString();

                obs.event(Observation.Event.of("jwt.valid"));

                return JwtClaims.builder()
                        .userId(userId)
                        .jti(jti)
                        .roles(List.of("USER"))
                        .accountStatus(accountStatus)
                        .expiresAt(verified.getExpiresAtAsInstant())
                        .issuedAt(verified.getIssuedAtAsInstant())
                        .build();

            } catch (TokenExpiredException e) {
                log.debug("Cognito JWT rejected — token expired");
                return null;
            } catch (JWTVerificationException e) {
                log.warn("Cognito JWT rejected — verification failed: {}", e.getMessage());
                return null;
            } finally {
                obs.stop();
            }
        }).filter(claims -> claims != null);
    }
}
