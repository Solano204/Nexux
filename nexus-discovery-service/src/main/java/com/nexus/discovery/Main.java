package com.nexus.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class Main {

    public static void main(String[] args) {
        // Bypass estricto en el arranque del hilo principal
        System.setProperty("spring.classformat.ignore", "true");

        SpringApplication.run(Main.class, args);
    }
}