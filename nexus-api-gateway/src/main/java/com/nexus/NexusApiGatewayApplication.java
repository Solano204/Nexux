package com.nexus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // Required for JwksCache @Scheduled refresh
public class NexusApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusApiGatewayApplication.class, args);
    }
}