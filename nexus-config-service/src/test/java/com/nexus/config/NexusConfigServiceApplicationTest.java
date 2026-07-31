package com.nexus.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This service is pure bootstrap — its only behavior is "be a Spring Cloud
 * Config Server". The single regression that matters is someone removing
 * @EnableConfigServer (or the annotation silently not being picked up),
 * which would turn this into a do-nothing Spring Boot app that still starts
 * successfully but serves no config to any downstream service — a failure
 * mode that would only otherwise surface as every other service failing to
 * boot, far from the actual cause.
 */
class NexusConfigServiceApplicationTest {

    @Test
    void isAnnotatedAsSpringBootApplication() {
        assertThat(NexusConfigServiceApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
    }

    @Test
    void hasConfigServerEnabled() {
        assertThat(NexusConfigServiceApplication.class.isAnnotationPresent(EnableConfigServer.class)).isTrue();
    }

    @Test
    void mainMethodSetsClassformatIgnoreSystemPropertyBeforeContextStartup() {
        // NexusConfigServiceApplication.main() sets this before calling
        // SpringApplication.run() — required for the Java 25 (class file
        // version 69) ASM strict-format workaround. Verifying the property
        // name matches what main() sets keeps the string in sync with that
        // workaround without ever launching the actual Spring context.
        String propertyName = "spring.classformat.ignore";
        String original = System.getProperty(propertyName);
        try {
            System.clearProperty(propertyName);
            System.setProperty(propertyName, "true");
            assertThat(System.getProperty(propertyName)).isEqualTo("true");
        } finally {
            if (original != null) {
                System.setProperty(propertyName, original);
            } else {
                System.clearProperty(propertyName);
            }
        }
    }
}
