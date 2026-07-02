# nexus-ledger-service — Source Code Bugs Found

Three bugs were found during code review. All three will prevent the service from running correctly until fixed.

---

## BUG 1 (CRITICAL) — Wrong Flyway migration directory name

**File:** `src/main/resources/` directory structure  
**Severity:** 🔴 CRITICAL — service fails to start; Flyway cannot find migrations

**Problem:**  
The migration SQL files are in `src/main/resources/db.migration/` (dot separator).  
`application.yml` tells Flyway to look in `classpath:db/migration` (slash separator).

Flyway resolves `classpath:db/migration` as `src/main/resources/db/migration/` — a directory that does not exist. Result: Flyway finds zero migrations and either errors out or runs against an empty baseline.

**Fix:** Rename the directory from `db.migration` to `db/migration`:

```bash
# From inside nexus-ledger-service/src/main/resources/
mkdir -p db/migration
mv db.migration/V1__create_ledger_entries.sql     db/migration/
mv db.migration/V2__create_postings.sql            db/migration/
mv db.migration/V3__create_chart_of_accounts.sql   db/migration/
mv db.migration/V4__create_reconciliation_snapshots.sql db/migration/
mv db.migration/V5__create_outbox.sql              db/migration/
rmdir db.migration
```

Or in your IDE: right-click `db.migration` → Rename → `migration`, then move it inside a new `db/` folder.

**Why `application.yml` is correct:**  
`classpath:db/migration` is the Spring/Maven convention. The `db.migration` name is a common IntelliJ artifact from creating a "package" instead of a directory.

---

## BUG 2 (CRITICAL) — Main.java is an IntelliJ placeholder, not a Spring Boot entry point

**File:** `src/main/java/com/nexus/ledger/Main.java`  
**Severity:** 🔴 CRITICAL — service cannot start at all

**Problem:**  
`Main.java` is the default IntelliJ "Hello World" template that was never replaced:

```java
// CURRENT (wrong):
public class Main {
    static void main() {          // Not public, no String[] args
        IO.println("Hello and welcome!");  // IO doesn't exist
        for (int i = 1; i <= 5; i++) { ... }
    }
}
```

This is not a Spring Boot application. `IO` is not a standard Java class (it's an IntelliJ scratch-file helper), and the method signature is wrong.

**Fix:** Replace `Main.java` entirely with a proper Spring Boot entry point:

```java
package com.nexus.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // Required for ReconciliationJobService @Scheduled cron
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
```

Note: `@EnableScheduling` is required for `ReconciliationJobService` to run its nightly 1:00 AM reconciliation cron job.

---

## BUG 3 (MEDIUM) — Missing @EnableScheduling causes silent reconciliation failure

**File:** `src/main/java/com/nexus/ledger/Main.java` (once Bug 2 is fixed)  
**Severity:** 🟡 MEDIUM — service starts fine but nightly reconciliation never runs

**Problem:**  
`ReconciliationJobService` uses `@Scheduled` annotations for the nightly financial integrity check. Spring's `@Scheduled` support requires `@EnableScheduling` on a `@Configuration` or `@SpringBootApplication` class. Without it, all `@Scheduled` methods are silently ignored — no error, no warning, jobs just never execute.

**Fix:** Add `@EnableScheduling` to the main application class (already shown in the Bug 2 fix above).

**How to verify it's working after the fix:**
```bash
# Check that the reconciliation job is registered:
curl http://localhost:8088/actuator/scheduledtasks
# Should show the reconciliation cron: "0 0 1 * * *" (1:00 AM daily)
```

---

## Summary checklist

- [ ] **BUG 1:** Rename `src/main/resources/db.migration/` → `src/main/resources/db/migration/`
- [ ] **BUG 2:** Replace `Main.java` with proper `@SpringBootApplication` entry point
- [ ] **BUG 3:** Add `@EnableScheduling` to the main class (already included in Bug 2 fix)

Fix Bug 1 and Bug 2 before attempting to run the service. Bug 3 is automatically fixed when Bug 2 is resolved using the replacement code above.
