package com.nexus.auth.lambda.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.auth.lambda.AuthLambdaHandler;
import com.nexus.auth.lambda.dynamo.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Session Extension Handler — extend session TTL without new tokens.
 */
public class SessionExtensionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(SessionExtensionHandler.class);

    private final SessionRepository sessionRepo;
    private final ObjectMapper mapper;

    public SessionExtensionHandler(SessionRepository sessionRepo,
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
            log.error("Session extension error: {}", e.getMessage(), e);
            return AuthLambdaHandler.errorResponse(500,
                    "EXTENSION_FAILED", "Session extension failed");
        }
    }
}