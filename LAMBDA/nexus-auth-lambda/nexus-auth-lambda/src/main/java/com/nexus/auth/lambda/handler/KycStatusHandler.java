package com.nexus.auth.lambda.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.xray.AWSXRay;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.auth.lambda.AuthLambdaHandler;
import com.nexus.auth.lambda.bridge.LocalPlaneBridgeClient;
import com.nexus.auth.lambda.dynamo.SessionRepository;
import com.nexus.auth.lambda.model.KycStatusResult;
import com.nexus.auth.lambda.model.SessionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * KYC Status Handler — bridge to local plane identity service.
 *
 * Cache-first: read from DynamoDB session (updated during token refresh).
 * Stale threshold: 1 hour — sync from local plane if cache is older.
 * Fallback: return stale data with X-Stale-Data header if local plane down.
 */
public class KycStatusHandler {

    private static final Logger log =
            LoggerFactory.getLogger(KycStatusHandler.class);

    private static final Duration KYC_CACHE_TTL =
            Duration.ofHours(1);

    private final SessionRepository sessionRepo;
    private final LocalPlaneBridgeClient bridgeClient;
    private final ObjectMapper mapper;

    public KycStatusHandler(SessionRepository sessionRepo,
                            LocalPlaneBridgeClient bridgeClient,
                            ObjectMapper mapper) {
        this.sessionRepo = sessionRepo;
        this.bridgeClient = bridgeClient;
        this.mapper = mapper;
    }

    public APIGatewayV2HTTPResponse handle(
            APIGatewayV2HTTPEvent event) {

        var segment = AWSXRay.beginSubsegment("KycStatusCheck");

        try {
            // Extract userId from path: /auth/kyc-status/{userId}
            Map<String, String> pathParams = event.getPathParameters();
            String userId = pathParams != null
                    ? pathParams.get("userId") : null;

            if (userId == null || userId.isBlank()) {
                return AuthLambdaHandler.errorResponse(400,
                        "MISSING_USER_ID", "userId path param required");
            }

            // ── Check DynamoDB cache first ─────────────────────
            Optional<SessionRecord> session = sessionRepo
                    .findByUserId(userId);

            if (session.isPresent() &&
                    !isCacheStale(session.get())) {

                log.debug("KYC status from cache: userId={}",
                        userId);
                segment.putMetadata("source", "cache");

                return AuthLambdaHandler.successResponse(200,
                        KycStatusResult.fromSession(session.get()),
                        mapper);
            }

            // ── Cache miss or stale — call local plane ─────────
            segment.putMetadata("source", "local_plane");

            Optional<KycStatusResult> kycResult = bridgeClient
                    .fetchKycStatus(userId);

            if (kycResult.isPresent()) {
                // Update DynamoDB session cache
                session.ifPresent(s ->
                        sessionRepo.updateKycStatus(
                                s.sessionId(), kycResult.get()));

                return AuthLambdaHandler.successResponse(200,
                        kycResult.get(), mapper);
            }

            // ── Local plane unavailable — fallback to stale ────
            if (session.isPresent()) {
                log.warn("Local plane unavailable, " +
                        "returning stale KYC: userId={}", userId);

                var headers = AuthLambdaHandler.jsonHeaders();
                var mutableHeaders = new java.util.HashMap<>(headers);
                mutableHeaders.put("X-Stale-Data", "true");
                mutableHeaders.put("X-Data-Age-Minutes",
                        String.valueOf(
                                getCacheAgeMinutes(session.get())));

                return com.amazonaws.services.lambda.runtime.events
                        .APIGatewayV2HTTPResponse.builder()
                        .withStatusCode(200)
                        .withHeaders(mutableHeaders)
                        .withBody(mapper.writeValueAsString(
                                KycStatusResult.fromSession(
                                        session.get())))
                        .build();
            }

            return AuthLambdaHandler.errorResponse(503,
                    "LOCAL_PLANE_UNAVAILABLE",
                    "KYC status temporarily unavailable");

        } catch (Exception e) {
            segment.addException(e);
            log.error("KYC status error: {}", e.getMessage(), e);
            return AuthLambdaHandler.errorResponse(500,
                    "INTERNAL_ERROR", "KYC status check failed");
        } finally {
            AWSXRay.endSubsegment();
        }
    }

    private boolean isCacheStale(SessionRecord session) {
        if (session.lastKycSyncAt() == null) return true;
        return Duration.between(session.lastKycSyncAt(),
                Instant.now()).compareTo(KYC_CACHE_TTL) > 0;
    }

    private long getCacheAgeMinutes(SessionRecord session) {
        if (session.lastKycSyncAt() == null) return Long.MAX_VALUE;
        return Duration.between(session.lastKycSyncAt(),
                Instant.now()).toMinutes();
    }
}