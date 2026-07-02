package com.nexus.auth.lambda.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.auth.lambda.AuthLambdaHandler;
import com.nexus.auth.lambda.dynamo.RevokedTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Token Revocation Handler — mark a JTI as revoked on logout
 * or token compromise.
 */
public class TokenRevocationHandler {

    private static final Logger log =
            LoggerFactory.getLogger(TokenRevocationHandler.class);

    private final RevokedTokenRepository revokedRepo;
    private final ObjectMapper mapper;

    public TokenRevocationHandler(RevokedTokenRepository revokedRepo,
                                  ObjectMapper mapper) {
        this.revokedRepo = revokedRepo;
        this.mapper = mapper;
    }

    public APIGatewayV2HTTPResponse handle(
            APIGatewayV2HTTPEvent event) {
        try {
            JsonNode body = mapper.readTree(event.getBody());
            String jti    = body.path("jti").asText(null);
            String reason = body.path("reason").asText("LOGOUT");

            if (jti == null || jti.isBlank()) {
                return AuthLambdaHandler.errorResponse(400,
                        "MISSING_JTI", "jti is required");
            }

            revokedRepo.revoke(jti, reason);

            return AuthLambdaHandler.successResponse(200,
                    Map.of("revoked", true,
                            "revokedAt", Instant.now().toString()),
                    mapper);

        } catch (Exception e) {
            log.error("Revocation error: {}", e.getMessage(), e);
            return AuthLambdaHandler.errorResponse(500,
                    "REVOCATION_FAILED", "Revocation failed");
        }
    }
}