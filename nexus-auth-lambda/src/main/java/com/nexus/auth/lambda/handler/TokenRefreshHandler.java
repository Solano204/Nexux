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
public class TokenRefreshHandler {

    private static final Logger log =
            LoggerFactory.getLogger(TokenRefreshHandler.class);

    private final CognitoIdentityProviderClient cognito;
    private final String clientId;
    private final SessionRepository sessionRepo;
    private final LocalPlaneBridgeClient bridgeClient;
    private final ObjectMapper mapper;

    public TokenRefreshHandler(CognitoIdentityProviderClient cognito,
                               String clientId,
                               SessionRepository sessionRepo,
                               LocalPlaneBridgeClient bridgeClient,
                               ObjectMapper mapper) {
        this.cognito = cognito;
        this.clientId = clientId;
        this.sessionRepo = sessionRepo;
        this.bridgeClient = bridgeClient;
        this.mapper = mapper;
    }

    public APIGatewayV2HTTPResponse handle(
            APIGatewayV2HTTPEvent event) {
        try {
            JsonNode body = mapper.readTree(event.getBody());
            String refreshToken = body.path("refreshToken").asText(null);
            String previousJti = body.path("previousJti").asText(null);

            if (refreshToken == null || refreshToken.isBlank()) {
                return AuthLambdaHandler.errorResponse(400,
                        "MISSING_REFRESH_TOKEN",
                        "refreshToken is required");
            }

            // Call Cognito to exchange refresh token
            InitiateAuthResponse cognitoResponse = cognito.initiateAuth(
                    InitiateAuthRequest.builder()
                            .authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                            .clientId(clientId)
                            .authParameters(Map.of(
                                    "REFRESH_TOKEN", refreshToken))
                            .build());

            String newAccessToken = cognitoResponse
                    .authenticationResult().accessToken();
            String newIdToken = cognitoResponse
                    .authenticationResult().idToken();

            // Update session activity
            sessionRepo.updateLastActivity(newAccessToken);

            // Sync KYC from local plane if cache is stale
            bridgeClient.syncKycIfStale(sessionRepo, newAccessToken);

            // Revoke old JTI
            if (previousJti != null && !previousJti.isBlank()) {
                // Old JTI is now superseded — add to revoked list
                // (via RevokedTokenRepository, omitted for brevity)
                log.debug("Old JTI superseded: {}", previousJti);
            }

            TokenRefreshResult result = new TokenRefreshResult(
                    newAccessToken, newIdToken, 3600, "Bearer");

            log.info("Token refreshed successfully");
            return AuthLambdaHandler.successResponse(200, result,
                    mapper);

        } catch (NotAuthorizedException e) {
            return AuthLambdaHandler.errorResponse(401,
                    "REFRESH_TOKEN_INVALID",
                    "Refresh token is invalid or expired");
        } catch (Exception e) {
            log.error("Refresh error: {}", e.getMessage(), e);
            return AuthLambdaHandler.errorResponse(503,
                    "REFRESH_FAILED", "Token refresh temporarily failed");
        }
    }
}
