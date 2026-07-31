package com.nexus.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/**
 * AWS Configuration — S3 + SQS + Cognito clients.
 *
 * S3: stores uploaded identity documents (encrypted at rest, KMS key).
 * SQS: publishes KYC initiation messages to the Rekognition Lambda queue.
 * Cognito: mirrors Docker-plane users into the "Option B" auth pool
 *   (see AWS-DOCKER-WORKFLOWS/02_LOGIN_FLOW.md) via CognitoUserMirror.
 *
 * LocalStack support for local development:
 *   When AWS_ENDPOINT_OVERRIDE is set, all clients point to LocalStack.
 *   This allows running the full KYC flow locally without real AWS.
 *   In production: DefaultCredentialsProvider (IAM role / env vars).
 */
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
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(awsRegion))
                .httpClientBuilder(
                        software.amazon.awssdk.http.urlconnection
                                .UrlConnectionHttpClient.builder());

        if (!endpointOverride.isBlank()) {
            // LocalStack: override endpoint + use static credentials
            builder
                    .endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(
                                    accessKeyId.isBlank() ? "test" : accessKeyId,
                                    secretAccessKey.isBlank() ? "test" : secretAccessKey)))
                    .forcePathStyle(true);  // LocalStack requires path-style
        } else {
            // Production: use IAM role / environment variable credentials
            builder.credentialsProvider(
                    DefaultCredentialsProvider.create());
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
            builder.credentialsProvider(
                    DefaultCredentialsProvider.create());
        }

        return builder.build();
    }

    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        var builder = CognitoIdentityProviderClient.builder()
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
            builder.credentialsProvider(
                    DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}