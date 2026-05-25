package com.nexus.config;





import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

/**
 * Nexus Config Service — Central configuration server.
 *
 * This is the ENTIRE application code.
 * Spring Cloud Config Server is infrastructure, not business logic.
 * The value is in the configuration files it serves, not in Java code.
 *
 * @EnableConfigServer: activates the config server HTTP endpoints
 *   GET /{application}/{profile}         → merged config
 *   GET /{application}/{profile}/{label} → config at Git label/branch
 *   GET /{application}-{profile}.yml     → YAML format
 *   GET /{application}-{profile}.properties → properties format
 *
 * @EnableEurekaClient: registers with discovery for monitoring.
 * Services do NOT use discovery to find the config server —
 * they use the hardcoded URL in bootstrap.yml because service
 * discovery requires configuration to work, creating a
 * chicken-and-egg problem.
 */
@SpringBootApplication
@EnableConfigServer
@EnableEurekaClient
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
