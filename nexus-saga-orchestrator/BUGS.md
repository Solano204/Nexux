# nexus-saga-orchestrator — Source Code Bugs Found

Two bugs that prevent correct operation.

---

## BUG 1 (CRITICAL) — Main.java is an IntelliJ placeholder

**File:** `src/main/java/com/nexus/saga/Main.java`  
**Severity:** 🔴 CRITICAL — service cannot start

```java
// CURRENT (wrong):
public class Main {
    static void main() {          // Not public, no String[] args
        IO.println("Hello and welcome!");  // IO class does not exist
    }
}
```

**Fix — replace `Main.java` entirely:**

```java
package com.nexus.saga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // REQUIRED — SagaTimeoutMonitor uses @Scheduled(fixedDelay=5000)
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
```

**Note:** `@EnableScheduling` is mandatory. Without it, `SagaTimeoutMonitor` runs but its `@Scheduled(fixedDelay = 5000)` method is silently never called — saga timeouts are never detected and stuck transactions are never compensated. Funds remain reserved indefinitely.

---

## BUG 2 (MEDIUM) — Dockerfile missing `-XX:+ZGenerational` and has undersized heap

**File:** `Dockerfile`  
**Severity:** 🟡 MEDIUM — builds and runs but uses wrong GC mode

**Problem 1:** `-XX:+UseZGC` without `-XX:+ZGenerational` in Java 25 uses deprecated non-generational ZGC. Java 25 defaults to generational ZGC but the flag should be explicit.

**Problem 2:** `-Xmx512m` is too small for the SAGA orchestrator. This service:
- Holds saga state for all in-flight transactions in Hikari pool (40 connections)
- Stores `SagaStepHistory` JSONB columns via hypersistence-utils
- Deserializes Kafka payloads for 4 consumer groups simultaneously
- Spring AI context for failure explanation

512m will cause frequent GC pressure under moderate load. The replacement Dockerfile increases this to 768m and adds the missing JVM flags.

---

## Summary checklist

- [ ] **BUG 1:** Replace `Main.java` with proper `@SpringBootApplication` + `@EnableScheduling` entry point
- [ ] **BUG 2:** Replace `Dockerfile` with the fixed version in this zip

Bug 1 is a hard blocker (service won't start). Bug 2 will manifest as GC pressure and timeout detection failures in production.
