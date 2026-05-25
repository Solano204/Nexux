package com.nexus.auth.lambda.handler;

public class TokenRevocationHandler {

    private final com.nexus.auth.lambda.dynamo.RevokedTokenRepository
            revokedRepo;
    private final ObjectMapper mapper;

    TokenRevocationHandler(
            com.nexus.auth.lambda.dynamo.RevokedTokenRepository
                    revokedRepo,
            ObjectMapper mapper) {
        this.revokedRepo = revokedRepo;
        this.mapper = mapper;
    }

    public APIGatewayV2HTTPResponse handle(
            APIGatewayV2HTTPEvent event) {
        try {
            JsonNode body = mapper.readTree(event.getBody());
            String jti = body.path("jti").asText(null);
            String reason = body.path("reason").asText("LOGOUT");

            if (jti == null || jti.isBlank()) {
                return AuthLambdaHandler.errorResponse(400,
                        "MISSING_JTI", "jti is required");
            }

            revokedRepo.revoke(jti, reason);

            return AuthLambdaHandler.successResponse(200,
                    Map.of("revoked", true,
                            "revokedAt",
                            java.time.Instant.now().toString()),
                    mapper);

        } catch (Exception e) {
            return AuthLambdaHandler.errorResponse(500,
                    "REVOCATION_FAILED", "Revocation failed");
        }
    }
}
}