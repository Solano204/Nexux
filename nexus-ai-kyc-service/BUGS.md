# nexus-ai-kyc-service — Source Code Bugs Found

Two bugs that prevent correct operation.

---

## BUG 1 (CRITICAL) — Main.java is an IntelliJ placeholder

**File:** `src/main/java/com/nexus/kyc/Main.java`  
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
package com.nexus.kyc;

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

## BUG 2 (CRITICAL) — Flyway migration directory uses dot separator

**Severity:** 🔴 CRITICAL — Flyway finds zero migrations and errors on startup

**Problem:**  
Migration file is in `src/main/resources/db.migration/` (dot).  
`application.yml` configures `flyway.locations: classpath:db/migration` (slash).  
These resolve to different paths — Flyway scans an empty directory.

**Fix:**
```bash
cd nexus-ai-kyc-service/src/main/resources
mkdir -p db/migration
mv db.migration/V1__create_kyc_audit.sql db/migration/
rmdir db.migration
```

---

## Summary checklist

- [ ] **BUG 1:** Replace `Main.java` with proper `@SpringBootApplication` entry point
- [ ] **BUG 2:** Rename `src/main/resources/db.migration/` → `src/main/resources/db/migration/`

Fix both before running `docker compose up`.
