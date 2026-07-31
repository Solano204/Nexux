package com.nexus.transaction.integration.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers base for nexus-transaction-service integration tests.
 *
 * Image tags match docker-compose-prod.yml EXACTLY (pgvector/pgvector:pg16-bookworm,
 * confluentinc/cp-kafka:7.6.0) - not `latest`. `latest` drifts underneath you
 * between local runs and CI runs on different days, and a test suite that
 * passes against a Postgres/Kafka version you don't actually run in prod
 * isn't verifying what it claims to. See 04_TESTING_STRATEGY_CHANGES.md
 * Section 4 for the audit that found the previous (never-executed, fully
 * commented out) attempt at this used "pgvector/pgvector:pg16" - missing
 * the exact -bookworm variant prod runs.
 *
 * asCompatibleSubstituteFor("postgres") is required: PostgreSQLContainer
 * validates the image repository name against a known-compatible allowlist
 * and throws IllegalArgumentException for "pgvector/pgvector" otherwise -
 * this isn't optional configuration, the container fails to start without it.
 *
 * @Container fields are static, so Testcontainers starts each container
 * exactly ONCE per JVM (shared across every test class in this module that
 * extends this base), not once per class. Combined with @ServiceConnection
 * deriving spring.datasource.* / spring.kafka.bootstrap-servers from those
 * SAME container instances for every subclass, Spring's context cache sees
 * identical effective configuration across subclasses and reuses ONE
 * ApplicationContext for the whole run instead of one per test class - this
 * is what actually solves "multiplicación de contexto", not @ServiceConnection
 * alone (that only removes manual @DynamicPropertySource wiring; the reuse
 * comes from the static fields + consistent config across subclasses).
 */
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    // testcontainers:elasticsearch was already a test dependency but never
    // wired up here — transactionSearchRepository (@EnableElasticsearchRepositories)
    // needs a real ES connection, so every subclass's context failed to load
    // with a connection-refused instead of actually testing anything.
    @Container
    @ServiceConnection
    static final ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.13.0"))
            .withEnv("xpack.security.enabled", "false")
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    // application-test.yml hardcodes spring.data.redis.host/port to
    // localhost:6380 — nothing listens there in a clean test run, so
    // every Redis-dependent path (and the health indicator, dragging
    // overall /actuator/health to 503) failed with
    // RedisConnectionFailureException. Wire a real container instead.
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    // KafkaConfig and KafkaStreamsConfig both build their beans by hand
    // (ConsumerFactory/ProducerFactory/KafkaTemplate/KafkaStreamsConfiguration)
    // reading spring.kafka(.streams).bootstrap-servers via raw @Value
    // injection, instead of going through Spring Boot's auto-configured
    // Kafka beans. @ServiceConnection only rewrites bootstrap servers for
    // beans that consult KafkaConnectionDetails — it does NOT set the
    // literal property in the Environment — so @Value-based reads here
    // still fall back to the static nexus-kafka:9092/localhost:19092
    // defaults in application.yml and every send/consume hangs retrying
    // against a broker that doesn't exist. Set both properties explicitly.
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.streams.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
