package com.nexus.fraud.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fraud.infrastructure.http.TransactionServiceClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Velocity Check Tool — Real-time transaction velocity analysis.
 *
 * Queries the Transaction Service's Kafka Streams state store
 * (via HTTP interactive query) for rolling window aggregations.
 *
 * Windows: 5 minutes, 15 minutes, 60 minutes
 *
 * Anomaly thresholds:
 * - > 5 transactions in 5 min → HIGH_FREQUENCY_5MIN
 * - > MXN 2,000 in 5 min → HIGH_VALUE_5MIN
 * - > 20 transactions in 60 min → HIGH_FREQUENCY_60MIN
 *
 * Results cached in Redis (5-second TTL) to avoid repeated
 * identical queries within the same processing window.
 *
 * MANDATORY: called on every transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VelocityCheckTool {

    private final StringRedisTemplate redisTemplate;
    private final TransactionServiceClient transactionServiceClient;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    private static final BigDecimal THRESHOLD_5MIN_COUNT =
            new BigDecimal("5");
    private static final BigDecimal THRESHOLD_5MIN_AMOUNT =
            new BigDecimal("2000");
    private static final BigDecimal THRESHOLD_60MIN_COUNT =
            new BigDecimal("20");

    @Tool(
            name = "velocity_check_tool",
            description = """
            Gets real-time transaction velocity for a user.
            Returns count and total amount for the last 5, 15, and 60
            minutes from the Kafka Streams velocity state store.
            High velocity (>5 txns/5min or >MXN 2000/5min) is a
            strong fraud indicator. MANDATORY for every transaction.
            """
    )
    public String checkVelocity(
            @ToolParam(description = "User ID to check velocity for")
            String userId,
            @ToolParam(description = "Transaction ID for audit")
            String transactionId) {

        Observation obs = Observation.createNotStarted(
                "fraud.tool.velocity_check.internal",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            // Check Redis cache (5-second TTL)
            String cacheKey = "fraud:velocity:" + userId + ":" +
                    Instant.now().truncatedTo(ChronoUnit.SECONDS)
                            .getEpochSecond() / 5;

            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                obs.event(Observation.Event.of("velocity.cache.hit"));
                return cached;
            }

            // Query Transaction Service Kafka Streams
            VelocityData v5 = queryVelocity(userId, 5);
            VelocityData v15 = queryVelocity(userId, 15);
            VelocityData v60 = queryVelocity(userId, 60);

            // Analyze anomalies
            List<String> anomalies = new ArrayList<>();

            if (v5.count().compareTo(THRESHOLD_5MIN_COUNT) > 0) {
                anomalies.add("HIGH_FREQUENCY_5MIN: " +
                        v5.count() + " transactions");
            }
            if (v5.totalAmount().compareTo(THRESHOLD_5MIN_AMOUNT) > 0) {
                anomalies.add("HIGH_VALUE_5MIN: MXN " +
                        v5.totalAmount());
            }
            if (v60.count().compareTo(THRESHOLD_60MIN_COUNT) > 0) {
                anomalies.add("HIGH_FREQUENCY_60MIN: " +
                        v60.count() + " transactions");
            }

            String status = anomalies.isEmpty() ? "NORMAL"
                    : (anomalies.size() >= 2 ? "CRITICAL" : "ANOMALOUS");

            String result = objectMapper.writeValueAsString(
                    new VelocityResult(userId, v5, v15, v60,
                            anomalies, status));

            // Cache result
            redisTemplate.opsForValue().set(
                    cacheKey, result, Duration.ofSeconds(5));

            obs.event(Observation.Event.of("velocity." + status));
            log.debug("Velocity check: userId={} status={} " +
                    "anomalies={}", userId, status, anomalies.size());

            return result;

        } catch (Exception e) {
            obs.error(e);
            log.warn("Velocity check failed for userId={}: {}",
                    userId, e.getMessage());
            return """
                {"error": "VELOCITY_UNAVAILABLE",
                 "message": "Could not retrieve velocity data",
                 "status": "UNKNOWN",
                 "anomalies": ["VELOCITY_CHECK_FAILED"]}
                """;
        } finally {
            obs.stop();
        }
    }

    private VelocityData queryVelocity(String userId, int minutes) {
        try {
            JsonNode response = transactionServiceClient
                    .getVelocity(userId, minutes);

            if (response != null) {
                return new VelocityData(
                        new BigDecimal(response.path("count").asText("0")),
                        new BigDecimal(response.path("totalAmount")
                                .asText("0")),
                        minutes);
            }
        } catch (Exception e) {
            log.debug("Velocity query failed for {}min: {}",
                    minutes, e.getMessage());
        }
        return new VelocityData(BigDecimal.ZERO, BigDecimal.ZERO,
                minutes);
    }

    public record VelocityData(
            BigDecimal count, BigDecimal totalAmount, int windowMinutes) {}

    public record VelocityResult(
            String userId,
            VelocityData last5Minutes,
            VelocityData last15Minutes,
            VelocityData last60Minutes,
            List<String> anomalies,
            String status
    ) {}
}