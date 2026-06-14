# nexus-discovery-service — Source Code Bugs Found

## BUG 1 (CRITICAL) — Main.java has IntelliJ placeholder body

**File:** `src/main/java/com/nexus/discovery/Main.java`  
**Severity:** 🔴 CRITICAL — service cannot start

Unlike previous services where the annotations were also wrong, this one has the correct Spring Boot annotations (`@SpringBootApplication`, `@EnableEurekaServer`) but the **method body is the IntelliJ placeholder**:

```java
// CURRENT (wrong — annotations OK, body wrong):
@SpringBootApplication
@EnableEurekaServer
public class Main {
    static void main() {              // Not public, no String[] args
        IO.println("Hello...");       // IO class does not exist
        for (int i = 1; i <= 5; i++) {
            IO.println("i = " + i);   // compile error
        }
    }
}
```

**Fix — replace only the method (keep the annotations):**

```java
package com.nexus.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Nexus Discovery Service — Service registry for the platform.
 * @EnableEurekaServer activates Eureka registration/heartbeat/query endpoints.
 */
@SpringBootApplication
@EnableEurekaServer
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
```

---

## Summary

- [ ] **BUG 1:** Fix `Main.java` — replace the `static void main()` placeholder body with the correct `public static void main(String[] args)` entry point.

This is the only bug. The annotations at the top of the class are already correct.
