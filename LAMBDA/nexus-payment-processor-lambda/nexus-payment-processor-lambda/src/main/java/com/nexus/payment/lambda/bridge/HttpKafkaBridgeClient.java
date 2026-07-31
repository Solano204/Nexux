package com.nexus.payment.lambda.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.payment.lambda.model.NormalizedPaymentEvent;
import com.nexus.payment.lambda.model.enums.FailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration; /**
 * HTTP bridge — calls Plane A API Gateway to publish to Kafka.
 */
public class HttpKafkaBridgeClient extends KafkaBridgeClient {

    private static final Logger log =
            LoggerFactory.getLogger(HttpKafkaBridgeClient.class);

    private final HttpClient httpClient;
    private final String bridgeUrl;
    private final String bridgeSecret;

    HttpKafkaBridgeClient(String bridgeUrl, String bridgeSecret,
                          ObjectMapper mapper) {
        super(mapper);
        this.bridgeUrl = bridgeUrl;
        this.bridgeSecret = bridgeSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public KafkaBridgeResult publish(NormalizedPaymentEvent event) {
        long startMs = System.currentTimeMillis();
        try {
            String payload = mapper.writeValueAsString(event);

            String url = bridgeUrl +
                    "/internal/v1/bridge/publish" +
                    "?topic=" + TARGET_TOPIC +
                    "&key=" + event.userId();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Plane-Bridge-Secret", bridgeSecret)
                    .header("X-Source-Service",
                            "nexus-payment-processor-lambda")
                    .header("X-Event-Id", event.eventId())
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            long durationMs = System.currentTimeMillis() - startMs;

            if (response.statusCode() >= 200 &&
                    response.statusCode() < 300) {

                // Parse Kafka metadata from response
                var responseBody = mapper.readTree(
                        response.body());

                log.info("HTTP bridge success: eventId={} " +
                                "topic={} durationMs={}",
                        event.eventId(), TARGET_TOPIC, durationMs);

                return KafkaBridgeResult.success(
                        TARGET_TOPIC,
                        responseBody.path("partition").asInt(0),
                        responseBody.path("offset").asLong(-1),
                        durationMs);

            } else {
                log.error("HTTP bridge error: status={} body={}",
                        response.statusCode(), response.body());
                return KafkaBridgeResult.failure(
                        FailureReason.KAFKA_BRIDGE_UNAVAILABLE,
                        "HTTP " + response.statusCode() + ": " +
                                response.body());
            }

        } catch (java.net.http.HttpTimeoutException e) {
            return KafkaBridgeResult.failure(
                    FailureReason.KAFKA_BRIDGE_TIMEOUT,
                    "Bridge timeout after 10s");
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String reason = root.getMessage() != null
                    ? root.getMessage()
                    : root.getClass().getName();
            log.error("HTTP bridge exception: type={} reason={}",
                    e.getClass().getName(), reason, e);
            return KafkaBridgeResult.failure(
                    FailureReason.KAFKA_BRIDGE_UNAVAILABLE,
                    reason);
        }
    }
}
