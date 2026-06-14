package com.nexus.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
public class NexusConfigServiceApplication {

    public static void main(String[] args) {
        // ✅ SOLUCIÓN CRÍTICA: Desactivar la validación estricta de formato de ASM
        // Esto obliga a Spring a ignorar que la clase es versión 69 (Java 25) y procesarla de todos modos.
        System.setProperty("spring.classformat.ignore", "true");

        SpringApplication.run(NexusConfigServiceApplication.class, args);
    }
}