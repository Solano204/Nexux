# nexus-risk-scoring-service — Source Code Bugs Found

Two bugs that prevent correct operation.

---

## BUG 1 (CRITICAL) — Main.java is an IntelliJ placeholder

**File:** `src/main/java/com/nexus/risk/Main.java`  
**Severity:** 🔴 CRITICAL — service cannot start

Same pattern as `nexus-ledger-service`, `nexus-analytics-service`, `nexus-fraud-service`, and others:

```java
// CURRENT (wrong):
//TIP To <b>Run</b> code...
public class Main {
    static void main() {       // Not public, no String[] args
        //TIP Press...
```

**Fix — replace `Main.java` entirely:**

```java
package com.nexus.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // Required — NightlyRiskScoringJobTriggerService uses @Scheduled
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
```

`@EnableScheduling` is required for `NightlyRiskScoringJobTriggerService` to fire `@Scheduled` tasks. Without it, the nightly batch job silently never runs.

---

## BUG 2 (MEDIUM) — Dockerfile missing `-XX:+ZGenerational`

**File:** `Dockerfile`  
**Severity:** 🟡 MEDIUM — builds and runs, but uses deprecated non-generational ZGC mode in Java 25

The existing ENTRYPOINT uses `-XX:+UseZGC` without `-XX:+ZGenerational`. In Java 25, generational ZGC is the preferred mode and the non-generational form is deprecated. The fixed `Dockerfile` in this zip adds the missing flag.

---

## Summary checklist

- [ ] **BUG 1:** Replace `Main.java` with proper `@SpringBootApplication` + `@EnableScheduling`
- [ ] **BUG 2:** Replace `Dockerfile` with the fixed version in this zip

Fix Bug 1 before attempting to start the service.
