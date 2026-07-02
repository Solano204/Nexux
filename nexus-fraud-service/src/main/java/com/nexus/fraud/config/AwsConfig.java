package com.nexus.fraud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
public class AwsConfig {

    @Value("${nexus.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${nexus.aws.endpoint-override:}")
    private String endpointOverride;

    @Value("${nexus.aws.access-key-id:}")
    private String accessKeyId;

    @Value("${nexus.aws.secret-access-key:}")
    private String secretAccessKey;

    @Bean
    public SqsClient sqsClient() {
        var builder = SqsClient.builder()
                .region(Region.of(awsRegion))
                .httpClientBuilder(
                        software.amazon.awssdk.http.urlconnection
                                .UrlConnectionHttpClient.builder());

        if (!endpointOverride.isBlank()) {
            builder
                    .endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(
                                    accessKeyId.isBlank() ? "test" : accessKeyId,
                                    secretAccessKey.isBlank() ? "test" : secretAccessKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
