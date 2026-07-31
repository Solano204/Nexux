package com.nexus.gateway.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Composite JWT Validator — the actual bean JwtAuthenticationFilter
 * depends on. Peeks at the token's unverified `iss` claim to decide
 * whether it's a local-plane token ("Option A") or a Cognito ID token
 * ("Option B" — see AWS-DOCKER-WORKFLOWS/02_LOGIN_FLOW.md), then
 * delegates to the matching validator for full signature/claims
 * verification.
 *
 * The peek itself is unauthenticated — it only reads which key/issuer to
 * verify AGAINST. Actual trust decisions happen entirely inside
 * LocalJwtValidator / CognitoJwtValidator.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class CompositeJwtValidator implements JwtValidator {

    private static final String LOCAL_ISSUER = "nexus-identity-service";

    private final LocalJwtValidator localValidator;
    private final CognitoJwtValidator cognitoValidator;
    private final CognitoJwksCache cognitoJwksCache;

    @Override
    public Mono<JwtClaims> validate(String token) {
        DecodedJWT unverified;
        try {
            unverified = JWT.decode(token);
        } catch (JWTDecodeException e) {
            return Mono.empty();
        }

        String issuer = unverified.getIssuer();

        if (LOCAL_ISSUER.equals(issuer)) {
            return localValidator.validate(token);
        }

        if (cognitoJwksCache.isEnabled() && cognitoJwksCache.issuer().equals(issuer)) {
            return cognitoValidator.validate(token);
        }

        log.warn("JWT rejected — unrecognized issuer: {}", issuer);
        return Mono.empty();
    }
}
