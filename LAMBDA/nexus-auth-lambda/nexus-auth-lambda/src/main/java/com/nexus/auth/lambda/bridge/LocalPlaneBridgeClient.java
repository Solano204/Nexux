package com.nexus.auth.lambda.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.auth.lambda.dynamo.SessionRepository;
import com.nexus.auth.lambda.model.KycStatusResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Local Plane Bridge Client — HTTP calls to Plane A identity service.
 *
 * Purpose: sync KYC status from nexus-identity-service (local Docker)
 * into the DynamoDB session cache.
 *
 * Authentication: X-Plane-Bridge-Secret header (shared secret from
 * AWS Secrets Manager, validated by identity service).
 *
 * Timeout: 5 seconds. If local plane is unavailable, caller falls
 * back to stale DynamoDB cache — no exception thrown.
 *
 * Called only when DynamoDB cache is stale (>1 hour old).
 * Not called on every Lambda invocation.
 */
public class LocalPlaneBridgeClient {

    private static final Logger log =
            LoggerFactory.getLogger(LocalPlaneBridgeClient.class);

    private final HttpClient httpClient;
    private final String localPlaneUrl;
    private final String bridgeSecret;
    private final ObjectMapper mapper;

    public LocalPlaneBridgeClient(HttpClient httpClient,
                                  String localPlaneUrl,
                                  String bridgeSecret,
                                  ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.localPlaneUrl = localPlaneUrl;
        this.bridgeSecret = bridgeSecret;
        this.mapper = mapper;
    }

    public Optional<KycStatusResult> fetchKycStatus(String userId) {
        try {
            String url = localPlaneUrl +
                    "/internal/v1/users/" + userId + "/kyc/status";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Plane-Bridge-Secret", bridgeSecret)
                    .header("Content-Type", "application/json")
                    .header("X-Source-Service", "nexus-auth-lambda")
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode body = mapper.readTree(response.body());
                KycStatusResult result = new KycStatusResult(
                        body.path("isVerified").asBoolean(false),
                        body.path("verifiedAt").asText(null),
                        body.path("kycTier").asText("BASIC"),
                        body.path("daysSinceVerification").asInt(0),
                        body.path("reVerificationDue").asBoolean(false)
                );
                log.debug("KYC status from local plane: userId={}",
                        userId);
                return Optional.of(result);
            }

            log.warn("Local plane returned {}: userId={}",
                    response.statusCode(), userId);
            return Optional.empty();

        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("Local plane timeout for userId={}", userId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Bridge call failed: userId={} {}",
                    userId, e.getMessage());
            return Optional.empty();
        }
    }

    public void syncKycIfStale(SessionRepository sessionRepo,
                               String accessToken) {
        // Called after token refresh — sync KYC if cache is old
        // Implementation would extract userId from token, check
        // cache age, and call fetchKycStatus if stale
        log.debug("KYC sync check after token refresh");
    }
}
