package com.nexus.saga.integration.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers base for nexus-saga-orchestrator integration tests.
 * Same pattern as nexus-transaction-service's AbstractIntegrationTest (see
 * 04_TESTING_STRATEGY_CHANGES.md Section 4) - same exact image tags as
 * docker-compose-prod.yml, static @Container fields so the containers (and
 * therefore the Spring context) are shared across every test class in this
 * module instead of restarted per class.
 */
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16-bookworm")
                    .asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection
    protected static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    // @ServiceConnection reliably feeds Spring Boot's OWN autoconfigured
    // beans (e.g. the DataSource, via JdbcConnectionDetails), but
    // KafkaConfig here hand-builds its ConsumerFactory/ProducerFactory
    // (same pattern as every other service in this platform) via a raw
    // @Value("${spring.kafka.bootstrap-servers:...}") read - that reads the
    // literal property in the Environment, which application.yml's own
    // ${KAFKA_BOOTSTRAP_SERVERS:localhost:19092} default still occupies
    // unless something overrides it explicitly. Without this, every
    // consumer/producer connects to a nonexistent localhost:19092 broker
    // instead of the real Testcontainers one.
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
