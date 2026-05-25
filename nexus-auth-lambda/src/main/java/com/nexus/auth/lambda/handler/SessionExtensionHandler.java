package com.nexus.auth.lambda.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.auth.lambda.AuthLambdaHandler;
import com.nexus.auth.lambda.bridge.LocalPlaneBridgeClient;
import com.nexus.auth.lambda.dynamo.SessionRepository;
import com.nexus.auth.lambda.model.TokenRefreshResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.Map;

/**
 * Token Refresh Handler — exchange Cognito refresh token for new access token.
 *
 * Flow:
 * 1. Call Cognito REFRESH_TOKEN_AUTH flow
 * 2. Update session last-activity in DynamoDB
 * 3. Optionally sync KYC status from local plane (< once/hour)
 * 4. Revoke old JTI in revoked tokens table
 * 5. Return new access + ID tokens
 */
/**
 * Session Extension Handler — extend session TTL without new tokens.
 */
public class SessionExtensionHandler {

    private final SessionRepository sessionRepo;
    private final ObjectMapper mapper;

    SessionExtensionHandler(SessionRepository sessionRepo,
                            ObjectMapper mapper) {
        this.sessionRepo = sessionRepo;
        this.mapper = mapper;
    }

    public APIGatewayV2HTTPResponse handle(
            APIGatewayV2HTTPEvent event) {
        try {
            JsonNode body = mapper.readTree(event.getBody());
            String sessionId = body.path("sessionId").asText(null);

            if (sessionId == null) {
                return AuthLambdaHandler.errorResponse(400,
                        "MISSING_SESSION_ID", "sessionId required");
            }

            var extended = sessionRepo.extendSession(sessionId);

            if (extended.isEmpty()) {
                return AuthLambdaHandler.errorResponse(404,
                        "SESSION_NOT_FOUND", "Session not found");
            }

            return AuthLambdaHandler.successResponse(200,
                    Map.of("extended", true,
                            "newExpiresAt",
                            extended.get().expiresAt().toString()),
                    mapper);

        } catch (Exception e) {
            return AuthLambdaHandler.errorResponse(500,
                    "EXTENSION_FAILED", "Session extension failed");
        }
    }
}