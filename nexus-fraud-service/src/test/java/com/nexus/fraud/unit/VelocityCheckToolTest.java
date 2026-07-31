package com.nexus.fraud.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fraud.agent.tools.VelocityCheckTool;
import com.nexus.fraud.infrastructure.http.TransactionServiceClient;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VelocityCheckToolTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private TransactionServiceClient transactionServiceClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private VelocityCheckTool tool;

    @BeforeEach
    void setUp() {
        tool = new VelocityCheckTool(redisTemplate, transactionServiceClient, objectMapper, ObservationRegistry.NOOP);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private JsonNode velocityResponse(String count, String totalAmount) throws Exception {
        return objectMapper.readTree(String.format(
                "{\"count\":\"%s\",\"totalAmount\":\"%s\"}", count, totalAmount));
    }

    @Test
    void returnsCachedResultWithoutQueryingTransactionService() {
        when(valueOperations.get(anyString())).thenReturn("{\"status\":\"NORMAL\"}");

        String result = tool.checkVelocity("user-1", "txn-1");

        assertThat(result).isEqualTo("{\"status\":\"NORMAL\"}");
        verifyNoInteractions(transactionServiceClient);
    }

    @Test
    void returnsNormalStatusWhenUnderAllThresholds() throws Exception {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(transactionServiceClient.getVelocity("user-1", 5)).thenReturn(velocityResponse("2", "500"));
        when(transactionServiceClient.getVelocity("user-1", 15)).thenReturn(velocityResponse("3", "700"));
        when(transactionServiceClient.getVelocity("user-1", 60)).thenReturn(velocityResponse("5", "1200"));

        String result = tool.checkVelocity("user-1", "txn-1");

        assertThat(result).contains("\"status\":\"NORMAL\"");
        assertThat(result).contains("\"anomalies\":[]");
        verify(valueOperations).set(anyString(), eq(result), eq(Duration.ofSeconds(5)));
    }

    @Test
    void flagsAnomalousOnSingleThresholdBreach() throws Exception {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(transactionServiceClient.getVelocity("user-1", 5)).thenReturn(velocityResponse("8", "500"));
        when(transactionServiceClient.getVelocity("user-1", 15)).thenReturn(velocityResponse("8", "500"));
        when(transactionServiceClient.getVelocity("user-1", 60)).thenReturn(velocityResponse("8", "500"));

        String result = tool.checkVelocity("user-1", "txn-1");

        assertThat(result).contains("\"status\":\"ANOMALOUS\"");
        assertThat(result).contains("HIGH_FREQUENCY_5MIN");
    }

    @Test
    void flagsCriticalOnMultipleThresholdBreaches() throws Exception {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(transactionServiceClient.getVelocity("user-1", 5)).thenReturn(velocityResponse("8", "5000"));
        when(transactionServiceClient.getVelocity("user-1", 15)).thenReturn(velocityResponse("8", "5000"));
        when(transactionServiceClient.getVelocity("user-1", 60)).thenReturn(velocityResponse("8", "5000"));

        String result = tool.checkVelocity("user-1", "txn-1");

        assertThat(result).contains("\"status\":\"CRITICAL\"");
        assertThat(result).contains("HIGH_FREQUENCY_5MIN");
        assertThat(result).contains("HIGH_VALUE_5MIN");
    }

    @Test
    void flagsHighFrequency60MinBreach() throws Exception {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(transactionServiceClient.getVelocity("user-1", 5)).thenReturn(velocityResponse("1", "100"));
        when(transactionServiceClient.getVelocity("user-1", 15)).thenReturn(velocityResponse("2", "200"));
        when(transactionServiceClient.getVelocity("user-1", 60)).thenReturn(velocityResponse("25", "3000"));

        String result = tool.checkVelocity("user-1", "txn-1");

        assertThat(result).contains("HIGH_FREQUENCY_60MIN");
    }

    @Test
    void gracefullyDegradesWhenTransactionServiceCallThrows() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(transactionServiceClient.getVelocity(anyString(), anyInt()))
                .thenThrow(new RuntimeException("circuit open"));

        String result = tool.checkVelocity("user-1", "txn-1");

        // Individual window failures degrade to zero-count data (still a
        // valid NORMAL result), the tool only returns the hard UNKNOWN
        // fallback for errors outside queryVelocity's own try/catch.
        assertThat(result).contains("\"status\":\"NORMAL\"");
    }

    @Test
    void returnsUnknownFallbackWhenRedisCacheReadFails() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        String result = tool.checkVelocity("user-1", "txn-1");

        assertThat(result).contains("VELOCITY_UNAVAILABLE");
        assertThat(result).contains("\"status\": \"UNKNOWN\"");
    }
}
