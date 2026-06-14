# nexus-ai-assistant-service — Source Code Bugs Found

## BUG 1 (CRITICAL) — Main.java is an IntelliJ placeholder

**File:** `src/main/java/com/nexus/assistant/Main.java`  
**Severity:** 🔴 CRITICAL — service cannot start at all

**Problem:**  
`Main.java` is the default IntelliJ "Hello World" template, same issue as `nexus-ledger-service`:

```java
// CURRENT (wrong — service cannot start):
public class Main {
    static void main() {          // Not public, no String[] args
        IO.println("Hello and welcome!");  // IO class does not exist
    }
}
```

**Fix:** Replace `Main.java` entirely:

```java
package com.nexus.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // Required if you add @Scheduled methods later
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
```

---

## Summary

- [ ] **BUG 1:** Replace `Main.java` with the proper `@SpringBootApplication` entry point above.

This is the only source-code bug. Fix it before attempting `docker compose up`.
