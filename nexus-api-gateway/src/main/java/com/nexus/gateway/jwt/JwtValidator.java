package com.nexus.gateway.jwt;

import reactor.core.publisher.Mono;

/**
 * JWT Validator — validates a bearer token and returns its claims.
 *
 * Two issuers are accepted platform-wide (see
 * AWS-DOCKER-WORKFLOWS/02_LOGIN_FLOW.md "Option A/B"):
 *   - {@link LocalJwtValidator}: tokens signed by nexus-identity-service's
 *     local keystore (the default, always-available path).
 *   - {@link CognitoJwtValidator}: Cognito ID tokens from the "Option B"
 *     user pool, for users mirrored via CognitoUserMirror.
 *
 * {@link CompositeJwtValidator} is the actual bean injected into
 * JwtAuthenticationFilter — it picks which of the two to delegate to by
 * peeking at the token's unverified issuer claim.
 *
 * Implementations must return an empty Mono on ANY validation failure —
 * never throw. Error details are logged but not returned to callers
 * (prevents information leakage to attackers).
 */
public interface JwtValidator {
    Mono<JwtClaims> validate(String token);
}
