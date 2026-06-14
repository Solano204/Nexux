# nexus-audit-query-jvm — Source Code Bugs Found

## BUG 1 (CRITICAL) — Main.java is an IntelliJ placeholder

**File:** `src/main/java/com/nexus/audit/query/Main.java`  
**Severity:** 🔴 CRITICAL — service cannot start

Same issue as `nexus-ledger-service`, `nexus-ai-assistant-service`, `nexus-fraud-service`, and `nexus-analytics-service`:

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
package com.nexus.audit.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
```

---

## Summary

- [ ] **BUG 1:** Replace `Main.java` with proper `@SpringBootApplication` entry point

This is the only bug. Fix it before running `docker compose up`.
