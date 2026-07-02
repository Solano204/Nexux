# nexus-fraud-service — Source Code Bugs Found

Two bugs that prevent the service from running correctly.

---

## BUG 1 (CRITICAL) — Main.java is an IntelliJ placeholder

**File:** `src/main/java/com/nexus/fraud/Main.java`  
**Severity:** 🔴 CRITICAL — service cannot start

Same issue as `nexus-ledger-service` and `nexus-ai-assistant-service`:

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
package com.nexus.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // Needed if scheduled reconciliation is added later
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
```

---

## BUG 2 (CRITICAL) — Flyway migration directory uses dot separator

**Severity:** 🔴 CRITICAL — service fails to start; Flyway finds zero migrations

**Problem:**  
Migration files are in `src/main/resources/db.migration/` (dot).  
`application.yml` configures `flyway.locations: classpath:db/migration` (slash).  
These do not resolve to the same path — Flyway scans an empty directory and errors.

**Fix:** Rename the directory:

```bash
cd nexus-fraud-service/src/main/resources
mkdir -p db/migration
mv db.migration/V1__create_fraud_decisions.sql db/migration/
mv db.migration/V2__create_outbox.sql           db/migration/
rmdir db.migration
```

---

## Summary checklist

- [ ] **BUG 1:** Replace `Main.java` with proper `@SpringBootApplication` entry point
- [ ] **BUG 2:** Rename `src/main/resources/db.migration/` → `src/main/resources/db/migration/`

Fix both before running `docker compose up`.
