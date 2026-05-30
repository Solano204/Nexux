package com.nexus.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Nexus Config Service — Central configuration server.
 *
 * This is the ENTIRE application code.
 * Spring Cloud Config Server is infrastructure, not business logic.
 * The value is in the configuration files it serves, not in Java code.
 *
 * @EnableConfigServer activates the config server HTTP endpoints:
 *   GET /{application}/{profile}             → merged config
 *   GET /{application}/{profile}/{label}     → config at Git label/branch
 *   GET /{application}-{profile}.yml         → YAML format
 *   GET /{application}-{profile}.properties  → properties format
 *
 * Eureka client auto-configures from the classpath dependency —
 * no @EnableEurekaClient annotation needed since Spring Cloud 2022+.
 *
 * Services use a hardcoded config server URL in bootstrap.yml rather
 * than Eureka discovery: service discovery itself requires configuration
 * to bootstrap, creating a chicken-and-egg problem.
 */
@SpringBootApplication
@EnableConfigServer
public class NexusConfigServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusConfigServiceApplication.class, args);
    }
}