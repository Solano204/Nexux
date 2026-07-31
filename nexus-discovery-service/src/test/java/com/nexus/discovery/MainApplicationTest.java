package com.nexus.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This service is pure bootstrap — its only behavior is "be a Eureka
 * discovery server". The regression that matters is someone removing
 * @EnableEurekaServer, which would let the app boot successfully while
 * providing no service registry at all — every other service's Eureka
 * client would then fail to register/discover, a platform-wide symptom
 * whose root cause (this one annotation) is easy to miss without a test
 * that pins it directly.
 */
class MainApplicationTest {

    @Test
    void isAnnotatedAsSpringBootApplication() {
        assertThat(Main.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
    }

    @Test
    void hasEurekaServerEnabled() {
        assertThat(Main.class.isAnnotationPresent(EnableEurekaServer.class)).isTrue();
    }
}
