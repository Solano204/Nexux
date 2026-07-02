# nexus-analytics-service — Source Code Bugs Found

Two bugs that prevent the service from running.

---

## BUG 1 (CRITICAL) — Main.java is an IntelliJ placeholder

**File:** `src/main/java/com/nexus/analytics/Main.java`  
**Severity:** 🔴 CRITICAL — service cannot start

Same issue as `nexus-ledger-service`, `nexus-ai-assistant-service`, and `nexus-fraud-service`:

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
package com.nexus.analytics;

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

## BUG 2 (MEDIUM) — Dockerfile `mkdir` runs as non-root user after `USER nexus`

**File:** `Dockerfile`  
**Severity:** 🟡 MEDIUM — Docker build succeeds but container crashes at runtime

**Problem:**

```dockerfile
# CURRENT (wrong order):
RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus          ← switches to non-root user here
WORKDIR /app
COPY --from=builder --chown=nexus:nexus /build/target/...  app.jar
RUN mkdir -p /var/kafka-streams   ← /var/kafka-streams is owned by root, nexus cannot write here
```

The container starts with `nexus` user, then tries to create `/var/kafka-streams` — a path under `/var` which is owned by root. The `mkdir` during build may succeed (Alpine's `/var` is world-writable by default), but when Kafka Streams tries to write RocksDB state to `/var/kafka-streams` at runtime, it gets a permission denied error and all 6 stream topologies fail to start.

The replacement `Dockerfile` in this zip fixes the order: `mkdir` + `chown` run as root BEFORE switching to `nexus`, then the volume is owned by `nexus` when Streams tries to write.

---

## Summary checklist

- [ ] **BUG 1:** Replace `Main.java` with proper `@SpringBootApplication` entry point
- [ ] **BUG 2:** Replace `Dockerfile` with the fixed version included in this zip

Only Bug 1 is a hard blocker (service can't start at all). Bug 2 is a runtime crash that happens after startup when Kafka Streams initialises RocksDB.
