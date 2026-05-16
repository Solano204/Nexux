package com.nexus.auth.lambda.handler;

import com.auth0.jwt.*;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.*;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.xray.AWSXRay;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.auth.lambda.AuthLambdaHandler;
import com.nexus.auth.lambda.auth.JwksCache;
import com.nexus.auth.lambda.dynamo.RevokedTokenRepository;
import com.nexus.auth.lambda.dynamo.SessionRepository;
import com.nexus.auth.lambda.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.interfaces.RSAPublicKey;
import java.util.*;

/**
 * Token Validation Handler — core Lambda operation.
 *
 * Validates a Cognito JWT Bearer token:
 * 1. Decode JWT header to extract kid
 * 2. Fetch RSA public key from JWKS cache (O(1) — pre-loaded)
 * 3. Verify signature + claims (iss, client_id, token_use, exp)
 * 4. Check revocation in DynamoDB (O(1) key lookup)
 * 5. Fetch session from DynamoDB by cognitoSub
 * 6. Return TokenValidationResult with userId, roles, KYC status
 *
 * Target P99 latency: < 200ms (warm), < 500ms (SnapStart cold)
 */
public class TokenValidationHandler {

    private static final Logger log =
            LoggerFactory.getLogger(TokenValidationHandler.class);

    private final JwksCache jwksCache;
    private final SessionRepository sessionRepo;
    private final RevokedTokenRepository revokedRepo;
    private final String userPoolId;
    private final String clientId;
    private final String region;
    private final ObjectMapper mapper;

    public TokenValidationHandler(JwksCache jwksCache,
                                  SessionRepository sessionRepo,
                                  RevokedTokenRepository revokedRepo,
                                  String userPoolId,
                                  String clientId,
                                  String region,
                                  ObjectMapper mapper) {
        this.jwksCache = jwksCache;
        this.sessionRepo = sessionRepo;
        this.revokedRepo = revokedRepo;
        this.userPoolId = userPoolId;
        this.clientId = clientId;
        this.region = region;
        this.mapper = mapper;
    }

    public APIGatewayV2HTTPResponse handle(
            APIGatewayV2HTTPEvent event) {

        var segment = AWSXRay.beginSubsegment("ValidateToken");

        try {
            // ── Extract Bearer token ───────────────────────────
            String authHeader = Optional.ofNullable(
                            event.getHeaders())
                    .map(h -> h.get("authorization"))
                    .or(() -> Optional.ofNullable(event.getHeaders())
                            .map(h -> h.get("Authorization")))
                    .orElse("");

            if (!authHeader.startsWith("Bearer ")) {
                return AuthLambdaHandler.errorResponse(401,
                        "MISSING_TOKEN",
                        "Authorization: Bearer <token> required");
            }

            String token = authHeader.substring(7).trim();

            // ── Step 1: Decode header (unauthenticated) ────────
            DecodedJWT unverified;
            try {
                unverified = JWT.decode(token);
            } catch (JWTDecodeException e) {
                return AuthLambdaHandler.errorResponse(401,
                        "MALFORMED_TOKEN", "Token format invalid");
            }

            String kid = unverified.getKeyId();

            // ── Step 2: Get public key from JWKS cache ─────────
            RSAPublicKey publicKey = jwksCache.getPublicKey(kid)
                    .orElse(null);

            if (publicKey == null) {
                log.warn("Unknown kid: {}", kid);
                return AuthLambdaHandler.errorResponse(401,
                        "INVALID_TOKEN", "Unknown signing key");
            }

            // ── Step 3: Verify signature + claims ─────────────
            DecodedJWT verified;
            try {
                Algorithm algorithm = Algorithm.RSA256(
                        publicKey, null);

                String issuer = String.format(
                        "https://cognito-idp.%s.amazonaws.com/%s",
                        region, userPoolId);

                JWTVerifier verifier = JWT.require(algorithm)
                        .withIssuer(issuer)
                        .withClaim("client_id", clientId)
                        .withClaim("token_use", "access")
                        .build();

                verified = verifier.verify(token);

            } catch (TokenExpiredException e) {
                return AuthLambdaHandler.errorResponse(401,
                        "TOKEN_EXPIRED", "Token has expired");
            } catch (JWTVerificationException e) {
                log.warn("JWT verification failed: {}",
                        e.getMessage());
                return AuthLambdaHandler.errorResponse(401,
                        "INVALID_TOKEN", "Token verification failed");
            }

            // ── Step 4: Check revocation ───────────────────────
            String jti = verified.getId();
            if (jti != null && revokedRepo.isRevoked(jti)) {
                return AuthLambdaHandler.errorResponse(401,
                        "TOKEN_REVOKED", "Token has been revoked");
            }

            // ── Step 5: Fetch session ──────────────────────────
            String cognitoSub = verified.getSubject();
            SessionRecord session = sessionRepo
                    .findByCognitoSub(cognitoSub)
                    .orElse(null);

            if (session == null) {
                return AuthLambdaHandler.errorResponse(401,
                        "SESSION_NOT_FOUND",
                        "No active session found");
            }

            if (!"ACTIVE".equals(session.status())) {
                return AuthLambdaHandler.errorResponse(401,
                        "SESSION_INACTIVE",
                        "Session is " + session.status());
            }

            // ── Step 6: Build response ─────────────────────────
            TokenValidationResult result = new TokenValidationResult(
                    true,
                    session.userId(),
                    session.roles(),
                    session.accountStatus(),
                    session.kycVerified(),
                    session.sessionId(),
                    verified.getExpiresAtAsInstant()
            );

            segment.putMetadata("userId", session.userId());
            segment.putMetadata("kycVerified", session.kycVerified());

            log.info("Token validated: userId={} kycVerified={}",
                    session.userId(), session.kycVerified());

            return AuthLambdaHandler.successResponse(200, result,
                    mapper);

        } catch (Exception e) {
            segment.addException(e);
            log.error("Validation error: {}", e.getMessage(), e);
            return AuthLambdaHandler.errorResponse(500,
                    "INTERNAL_ERROR", "Validation failed");
        } finally {
            AWSXRay.endSubsegment();
        }
    }
}