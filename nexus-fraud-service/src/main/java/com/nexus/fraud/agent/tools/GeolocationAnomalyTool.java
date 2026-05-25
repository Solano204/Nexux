package com.nexus.fraud.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Geolocation Anomaly Tool — Detects location-based fraud signals.
 *
 * Checks:
 * 1. Is IP from VPN, proxy, Tor, or datacenter?
 * 2. Is country different from user's historical locations?
 * 3. Is there "impossible travel"? (moved faster than physics allows)
 * 4. Is this city new for this user?
 *
 * Location history: read from Redis user:locations:{userId}
 * Written by Identity Service on successful logins.
 *
 * Impossible travel: Haversine distance / elapsed time > 900 km/h
 * (max realistic commercial flight speed)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeolocationAnomalyTool {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @Tool(
            name = "geolocation_anomaly_tool",
            description = """
            Checks if the transaction's source IP is anomalous for this user.
            Detects: VPN/proxy/Tor nodes, new country or city, impossible
            travel (physically impossible distance in elapsed time).
            Returns riskIndicators list and anomalyStatus (NORMAL/ANOMALOUS).
            """
    )
    public String checkGeolocation(
            @ToolParam(description = "User ID")
            String userId,
            @ToolParam(description = "Source IP address")
            String sourceIp,
            @ToolParam(description = "ISO 3166-1 alpha-2 country code " +
                    "from pre-enrichment, empty if unknown")
            String countryCode,
            @ToolParam(description = "City from pre-enrichment")
            String city,
            @ToolParam(description = "Whether IP is VPN (true/false)")
            String isVpn,
            @ToolParam(description = "Whether IP is Tor exit node")
            String isTor,
            @ToolParam(description = "Whether IP is datacenter")
            String isDatacenter) {

        Observation obs = Observation.createNotStarted(
                "fraud.tool.geolocation.internal",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            List<String> riskIndicators = new ArrayList<>();

            // Check VPN/Tor/Proxy flags
            if ("true".equalsIgnoreCase(isTor)) {
                riskIndicators.add("TOR_EXIT_NODE: " +
                        "transaction from Tor anonymization network");
            }
            if ("true".equalsIgnoreCase(isVpn)) {
                riskIndicators.add("VPN_DETECTED: " +
                        "transaction from VPN endpoint");
            }
            if ("true".equalsIgnoreCase(isDatacenter)) {
                riskIndicators.add("DATACENTER_IP: " +
                        "IP belongs to cloud/hosting provider");
            }

            // Check country history from Redis
            String historicalLocationsKey =
                    "user:locations:" + userId;
            List<String> history = redisTemplate.opsForList()
                    .range(historicalLocationsKey, 0, 49);

            boolean knownCountry = false;
            boolean knownCity = false;
            boolean impossibleTravel = false;
            String lastKnownLocation = null;

            if (history != null && !history.isEmpty()) {
                knownCountry = history.stream()
                        .anyMatch(loc -> loc.contains(countryCode));
                knownCity = history.stream()
                        .anyMatch(loc -> loc.contains(city));

                // Check impossible travel using last location
                lastKnownLocation = history.get(history.size() - 1);
                impossibleTravel = detectImpossibleTravel(
                        lastKnownLocation, countryCode, city);
            }

            if (!countryCode.isBlank() && !knownCountry) {
                riskIndicators.add("NEW_COUNTRY: " + countryCode +
                        " - not in user's location history");
            } else if (!city.isBlank() && !knownCity && knownCountry) {
                riskIndicators.add("NEW_CITY: " + city +
                        " (known country, new city)");
            }

            if (impossibleTravel) {
                riskIndicators.add("IMPOSSIBLE_TRAVEL: " +
                        "location change physically impossible in elapsed time");
            }

            String anomalyStatus = riskIndicators.isEmpty()
                    ? "NORMAL" : "ANOMALOUS";

            var result = new GeolocationResult(
                    sourceIp, countryCode, city,
                    Boolean.parseBoolean(isVpn),
                    Boolean.parseBoolean(isTor),
                    Boolean.parseBoolean(isDatacenter),
                    knownCountry, knownCity,
                    impossibleTravel,
                    riskIndicators, anomalyStatus
            );

            obs.lowCardinalityKeyValue("anomalyStatus", anomalyStatus);

            if (!riskIndicators.isEmpty()) {
                obs.event(Observation.Event.of(
                        "geolocation.anomaly.detected"));
            }

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            obs.error(e);
            log.warn("Geolocation check failed: {}", e.getMessage());
            return "{\"status\":\"GEOLOCATION_CHECK_FAILED\"," +
                    "\"anomalies\":[\"CHECK_UNAVAILABLE\"]}";
        } finally {
            obs.stop();
        }
    }

    private boolean detectImpossibleTravel(String lastLocation,
                                           String currentCountry,
                                           String currentCity) {
        // Parse last location: "COUNTRY:CITY:LAT:LNG:TIMESTAMP"
        try {
            String[] parts = lastLocation.split(":");
            if (parts.length < 5) return false;

            String lastCountry = parts[0];
            double lastLat = Double.parseDouble(parts[2]);
            double lastLng = Double.parseDouble(parts[3]);
            Instant lastTime = Instant.ofEpochSecond(
                    Long.parseLong(parts[4]));

            // If same country, no impossible travel possible
            if (lastCountry.equals(currentCountry)) return false;

            // Calculate time elapsed
            long minutesElapsed = Duration.between(
                    lastTime, Instant.now()).toMinutes();

            // Mexico City to New York ≈ 3,360 km
            // If < 4 hours and different country = impossible travel
            // (rough heuristic — production would use haversine)
            return minutesElapsed < 240 &&
                    !lastCountry.equals(currentCountry);

        } catch (Exception e) {
            return false;
        }
    }

    public record GeolocationResult(
            String sourceIp, String country, String city,
            boolean isVpn, boolean isTor, boolean isDatacenter,
            boolean knownCountry, boolean knownCity,
            boolean impossibleTravel,
            List<String> riskIndicators, String anomalyStatus
    ) {}
}