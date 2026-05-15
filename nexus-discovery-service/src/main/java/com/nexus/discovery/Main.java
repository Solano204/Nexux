package com.nexus.discovery;



import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Nexus Discovery Service — Service registry for the platform.
 *
 * This is the ENTIRE application code.
 * Eureka Server is infrastructure — all behavior is configuration.
 *
 * @EnableEurekaServer activates:
 *   - Service registration endpoint: POST /eureka/apps/{name}
 *   - Heartbeat endpoint:            PUT  /eureka/apps/{name}/{id}
 *   - Registry fetch:                GET  /eureka/apps
 *   - Instance query:                GET  /eureka/apps/{name}
 *   - Deregistration:                DELETE /eureka/apps/{name}/{id}
 *   - Eureka dashboard:              GET  /
 *
 * Startup order: second service after nexus-config-service.
 * All 13 business services depend on this being healthy.
 *
 * Self-healing:
 * - Clients cache the registry locally (30s refresh)
 * - If this service goes down, cached registries keep routing working
 * - Services re-register automatically when this service recovers
 *
 * No AI, no database, no Kafka.
 * Pure in-memory registry with HTTP API.
 */
@SpringBootApplication
@EnableEurekaServer
//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    static void main() {
        //TIP Press <shortcut actionId="ShowIntentionActions"/> with your caret at the highlighted text
        // to see how IntelliJ IDEA suggests fixing it.
        IO.println(String.format("Hello and welcome!"));

        for (int i = 1; i <= 5; i++) {
            //TIP Press <shortcut actionId="Debug"/> to start debugging your code. We have set one <icon src="AllIcons.Debugger.Db_set_breakpoint"/> breakpoint
            // for you, but you can always add more by pressing <shortcut actionId="ToggleLineBreakpoint"/>.
            IO.println("i = " + i);
        }
    }
}
