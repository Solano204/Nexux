# audit-write-native — Source Code Bugs Found

Two bugs. The `application.properties` is the most critical — the Kafka consumers will not bind without it.

---

## BUG 1 (CRITICAL) — application.properties is completely empty

**File:** `src/main/resources/application.properties`  
**Severity:** 🔴 CRITICAL — service starts but ALL 15 Kafka consumers fail to bind

**Problem:**  
Quarkus SmallRye Reactive Messaging requires every `@Incoming("channel-name")` to have a corresponding `mp.messaging.incoming.channel-name.*` property block. Without these, Quarkus cannot connect the channel name to a Kafka topic.

The `application.properties` file is 0 bytes — none of the 15 channel bindings, Elasticsearch URL, MongoDB URI, or Redis host are configured. The service compiles but no events are ever consumed.

**Fix:** Use the complete `application.properties` file included in this zip. It configures all 15 Kafka channels, Elasticsearch, MongoDB, Redis, and observability.

---

## BUG 2 (CRITICAL) — Main.java is an IntelliJ placeholder

**File:** `src/main/java/com/nexus/audit/write/Main.java`  
**Severity:** 🔴 CRITICAL — service cannot compile or start

**Problem:**  
```java
// CURRENT (wrong):
public class Main {
    static void main() {          // Not public, no String[] args
        IO.println("Hello...");   // IO class does not exist
    }
}
```

**Fix for Quarkus (different from Spring Boot!):**

In Quarkus you don't need a main class at all — the framework provides it via the quarkus-maven-plugin. However, if you want to keep a `Main.java` for IDE support and `quarkus:dev` mode:

```java
package com.nexus.audit.write;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusMain;

/**
 * Optional entry point for IDE run configurations.
 * Quarkus does not require this class — the quarkus-maven-plugin
 * generates the real entry point during build.
 */
@QuarkusMain
public class Main {

    public static void main(String... args) {
        Quarkus.run(args);
    }
}
```

**Alternative (simplest fix):** Just delete `Main.java` entirely. Quarkus finds application beans via CDI — no main class is needed. The `AuditEventConsumer` and `ComplianceRuleEvaluator` are `@ApplicationScoped` and will be discovered automatically.

---

## Summary checklist

- [ ] **BUG 1:** Replace empty `application.properties` with the complete version in this zip
- [ ] **BUG 2:** Either delete `Main.java` or replace it with the `@QuarkusMain` version above

Fix Bug 1 first — it is the root cause of all consumer failures.
