package com.nexus.payment.lambda.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.payment.lambda.model.NormalizedPaymentEvent;
import com.nexus.payment.lambda.model.enums.FailureReason;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Kafka Bridge Client — two modes.
 *
 * MODE 1: HTTP bridge (default for local dev / no MSK)
 *   POST to Plane A API Gateway:
 *   /internal/v1/bridge/publish?topic=transactions.initiated
 *   Authenticated with X-Plane-Bridge-Secret header.
 *   Plane A API Gateway forwards to Kafka.
 *   Latency: ~100-200ms (VPC endpoint or internet).
 *
 * MODE 2: MSK direct (production with shared VPC)
 *   Lambda runs in the same VPC as MSK cluster.
 *   Kafka producer publishes directly to MSK broker.
 *   Latency: ~5-20ms.
 *   Requires: MSK_BOOTSTRAP_SERVERS env var.
 *
 * Both modes write to the same topic: transactions.initiated
 * Key: userId (ensures same user's events go to same partition)
 *
 * BRIDGE_FAILED result is RETRYABLE — message returns to SQS.
 */
public abstract class KafkaBridgeClient {

    protected final ObjectMapper mapper;
    protected static final String TARGET_TOPIC =
            "transactions.initiated";

    protected KafkaBridgeClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public abstract KafkaBridgeResult publish(
            NormalizedPaymentEvent event);

    public static KafkaBridgeClient create(String mode,
                                           ObjectMapper mapper) {
        if ("MSK".equalsIgnoreCase(mode)) {
            String bootstrapServers = System.getenv(
                    "MSK_BOOTSTRAP_SERVERS");
            return new MskKafkaBridgeClient(bootstrapServers, mapper);
        }
        String httpUrl = System.getenv("KAFKA_BRIDGE_HTTP_URL");
        String secret = System.getenv("PLANE_BRIDGE_SECRET");
        return new HttpKafkaBridgeClient(httpUrl, secret, mapper);
    }
}

