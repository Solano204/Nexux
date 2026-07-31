package com.nexus.kyc.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
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

    /**
     * Timeouts (resilience guide, Fase 1): used for the KYC-result callback
     * to identity-service — bounded so a hung callback can't stall this
     * service's own Kafka consumer processing indefinitely.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .readTimeout(java.time.Duration.ofSeconds(5))
                .build();
    }

    /**
     * Timeouts (Fase 5, see CHANGES-BESTPRACTICES/
     * 10_ARCHITECTURE_PATTERNS_CHANGES.md): downloadFromS3() runs on the
     * Kafka consumer thread itself (KycInitiationConsumer), BEFORE the
     * thread-pool-bulkhead.kyc-verification pool that protects the
     * OpenAI-dependent part of the pipeline - this client previously had
     * no timeout at all, so a slow/hung S3 could stall the identity.kyc
     * consumer indefinitely (and risk a rebalance past
     * max.poll.interval.ms), not just fail one verification.
     */
    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(awsRegion))
                .httpClientBuilder(
                        software.amazon.awssdk.http.urlconnection
                                .UrlConnectionHttpClient.builder()
                                .connectionTimeout(java.time.Duration.ofSeconds(3))
                                .socketTimeout(java.time.Duration.ofSeconds(10)));

        if (!endpointOverride.isBlank()) {
            builder
                    .endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(
                                    accessKeyId.isBlank() ? "test" : accessKeyId,
                                    secretAccessKey.isBlank() ? "test" : secretAccessKey)))
                    .forcePathStyle(true);
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }

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
