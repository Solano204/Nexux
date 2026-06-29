package com.nexus.identity;  // ← changed from com.nexus

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication  // ← remove scanBasePackages, no longer needed
@EnableDiscoveryClient
@EnableAsync
@EnableScheduling
public class NexusIdentityServiceApplication {
    public static void main(String[] args) {
        System.setProperty("spring.classformat.ignore", "true");
        SpringApplication.run(NexusIdentityServiceApplication.class, args);
    }
}