package com.nexus.transaction.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
@ConditionalOnProperty(name = "nexus.analytics.dynamodb.enabled",
        havingValue = "true", matchIfMissing = false)
public class DynamoDbConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(
            @Value("${aws.region:us-east-1}") String region) {
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .build();
    }
}
