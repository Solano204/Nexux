package com.nexus.auth.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.auth.lambda.auth.JwksCache;
import com.nexus.auth.lambda.bridge.LocalPlaneBridgeClient;
import com.nexus.auth.lambda.dynamo.RevokedTokenRepository;
import com.nexus.auth.lambda.dynamo.SessionRepository;
import com.nexus.auth.lambda.handler.*;
import org.crac.Core;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Auth Lambda Handler — main entry point with SnapStart.
 *
 * SnapStart lifecycle:
 * 1. Lambda publishes a new version
 * 2. AWS initializes the JVM, runs static blocks, loads classes
 * 3. CRaC beforeCheckpoint() is called — we close connections
 * 4. AWS takes a memory snapshot of the initialized JVM
 * 5. On cold start: AWS restores JVM from snapshot (~200ms)
 * 6. CRaC afterRestore() is called — we re-open connections,
 *    refresh time-sensitive caches (JWKS keys)
 * 7. Handler ready — total cold start: ~300-500ms (vs 5-15s without)
 */
public class AuthLambdaHandler
        implements RequestHandler<APIGatewayV2HTTPEvent,
        APIGatewayV2HTTPResponse>,
        Resource {

    private static final Logger log =
            LoggerFactory.getLogger(AuthLambdaHandler.class);

    // ── Static fields: initialized once at class load ──────
    private static final ObjectMapper MAPPER;
    private static final JwksCache JWKS_CACHE;
    private static final DynamoDbClient DYNAMO;
    private static final CognitoIdentityProviderClient COGNITO;
    private static final SessionRepository SESSION_REPO;
    private static final RevokedTokenRepository REVOKED_REPO;
    private static final LocalPlaneBridgeClient BRIDGE_CLIENT;

    private static final String USER_POOL_ID;
    private static final String CLIENT_ID;
    private static final String AWS_REGION_STR;

    // HTTP client re-initialized after CRaC restore
    private static volatile HttpClient httpClient;

    static {
        USER_POOL_ID  = System.getenv("COGNITO_USER_POOL_ID");
        CLIENT_ID     = System.getenv("COGNITO_CLIENT_ID");
        AWS_REGION_STR = System.getenv("AWS_REGION");

        MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind
                        .SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        DYNAMO = DynamoDbClient.builder()
                .region(Region.of(AWS_REGION_STR))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        COGNITO = CognitoIdentityProviderClient.builder()
                .region(Region.of(AWS_REGION_STR))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        String sessionsTable = System.getenv("DYNAMODB_SESSIONS_TABLE");
        String revokedTable  = System.getenv("DYNAMODB_REVOKED_TOKENS_TABLE");

        SESSION_REPO = new SessionRepository(DYNAMO, sessionsTable, MAPPER);
        REVOKED_REPO = new RevokedTokenRepository(DYNAMO, revokedTable);

        String jwksUrl = String.format(
                "https://cognito-idp.%s.amazonaws.com/%s/.well-known/jwks.json",
                AWS_REGION_STR, USER_POOL_ID);
        JWKS_CACHE = JwksCache.initialize(jwksUrl);

        httpClient = buildHttpClient();

        String localPlaneUrl  = System.getenv("LOCAL_PLANE_IDENTITY_URL");
        String bridgeSecret   = System.getenv("PLANE_BRIDGE_SECRET");
        BRIDGE_CLIENT = new LocalPlaneBridgeClient(
                httpClient, localPlaneUrl, bridgeSecret, MAPPER);

        log.info("Auth Lambda initialized: userPool={} region={}",
                USER_POOL_ID, AWS_REGION_STR);
    }

    public AuthLambdaHandler() {
        Core.getGlobalContext().register(this);
        log.info("Registered with CRaC for SnapStart");
    }

    // ── CRaC Resource implementation ──────────────────────

    @Override
    public void beforeCheckpoint(
            org.crac.Context<? extends Resource> context)
            throws Exception {
        log.info("CRaC beforeCheckpoint: closing connections");
        // HttpClient recreated after restore — nothing explicit needed
    }

    @Override
    public void afterRestore(
            org.crac.Context<? extends Resource> context)
            throws Exception {
        log.info("CRaC afterRestore: refreshing post-snapshot state");
        httpClient = buildHttpClient();
        JWKS_CACHE.refreshIfStale();
        log.info("Auth Lambda restored from SnapStart snapshot");
    }

    // ── Main handler ──────────────────────────────────────

    @Override
    public APIGatewayV2HTTPResponse handleRequest(
            APIGatewayV2HTTPEvent event, Context context) {

        String path   = event.getRawPath();
        String method = event.getRequestContext().getHttp().getMethod();

        log.info("Auth Lambda invoked: method={} path={}", method, path);

        try {
            return route(event, path, method);
        } catch (Exception e) {
            log.error("Unhandled exception: {}", e.getMessage(), e);
            return errorResponse(500, "INTERNAL_ERROR",
                    "Internal server error");
        }
    }

    private APIGatewayV2HTTPResponse route(
            APIGatewayV2HTTPEvent event,
            String path, String method) {

        if ("POST".equals(method) && "/auth/validate".equals(path)) {
            return new TokenValidationHandler(
                    JWKS_CACHE, SESSION_REPO, REVOKED_REPO,
                    USER_POOL_ID, CLIENT_ID, AWS_REGION_STR, MAPPER)
                    .handle(event);
        }

        if ("POST".equals(method) && "/auth/refresh".equals(path)) {
            return new TokenRefreshHandler(
                    COGNITO, CLIENT_ID, SESSION_REPO, REVOKED_REPO,
                    BRIDGE_CLIENT, MAPPER)
                    .handle(event);
        }

        if ("POST".equals(method) &&
                "/auth/extend-session".equals(path)) {
            return new SessionExtensionHandler(SESSION_REPO, MAPPER)
                    .handle(event);
        }

        if ("POST".equals(method) && "/auth/revoke".equals(path)) {
            return new TokenRevocationHandler(REVOKED_REPO, MAPPER)
                    .handle(event);
        }

        if ("GET".equals(method) &&
                path.startsWith("/auth/kyc-status/")) {
            return new KycStatusHandler(
                    SESSION_REPO, BRIDGE_CLIENT, MAPPER)
                    .handle(event);
        }

        return errorResponse(404, "NOT_FOUND",
                "No handler for: " + method + " " + path);
    }

    // ── Helpers — public so handler classes can call them ─

    public static APIGatewayV2HTTPResponse errorResponse(
            int statusCode, String code, String message) {
        try {
            String body = MAPPER.writeValueAsString(
                    Map.of("error", code, "message", message));
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(statusCode)
                    .withHeaders(jsonHeaders())
                    .withBody(body)
                    .build();
        } catch (Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withBody("{\"error\":\"SERIALIZATION_ERROR\"}")
                    .build();
        }
    }

    public static APIGatewayV2HTTPResponse successResponse(
            int statusCode, Object body, ObjectMapper mapper) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(statusCode)
                    .withHeaders(jsonHeaders())
                    .withBody(mapper.writeValueAsString(body))
                    .build();
        } catch (Exception e) {
            return errorResponse(500, "SERIALIZATION_ERROR",
                    "Failed to serialize response");
        }
    }

    public static Map<String, String> jsonHeaders() {
        return Map.of(
                "Content-Type", "application/json",
                "X-Lambda-Function", "nexus-auth-lambda"
        );
    }

    private static HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}