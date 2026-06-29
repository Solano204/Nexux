# audit-write-native — Complete Dependency Map

## Port: 8096  |  Stack: Quarkus native (NOT Spring Boot)

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                             |
|-------------------|-------|-----------------------------------------------------------------|
| MongoDB           | 27019 | nexus_audit — primary audit document store                      |
| Elasticsearch     | 9202  | nexus-audit-* — secondary audit index (dual-write)              |
| Kafka             | 19092 | consumes 15 topics covering ALL platform events                 |
| Config service    | 8888  | NOT used — Quarkus reads its own application.properties         |
| Discovery service | 8761  | NOT used — Quarkus does not register with Eureka by default     |

**NO PostgreSQL, NO Redis, NO S3, NO SQS, NO OpenAI**

---

## Kafka topics CONSUMED (ALL platform events)

| Topic                  | Source service               |
|------------------------|------------------------------|
| transactions.completed | transaction-service          |
| transactions.failed    | transaction-service          |
| transactions.initiated | transaction-service          |
| ledger.posted          | ledger-service               |
| ledger.reversed        | ledger-service               |
| fraud.result           | fraud-service                |
| fraud.flagged          | fraud-service                |
| account.frozen         | account-service              |
| accounts.created       | account-service              |
| users.registered       | identity-service             |
| identity.verified      | ai-kyc-service               |
| identity.rejected      | ai-kyc-service               |
| saga.completed         | saga-orchestrator            |
| ai-query-logged        | ai-assistant-service         |
| analytics.anomalies    | analytics-service            |

---

## Kafka topics PRODUCED

None — this is a pure write service. It only consumes.

---

## HTTP endpoints

| Path       | Returns                                |
|------------|----------------------------------------|
| GET /q/health | Quarkus health check (NOT /actuator/health) |

No user-facing or service-to-service endpoints. Write-only.

---

## Dual-write behavior

Every Kafka event is written to BOTH stores simultaneously:
```
Kafka event → audit-write-native → MongoDB  (nexus_audit collection)
                                 → Elasticsearch (nexus-audit-YYYY-MM index)
```

If one write fails, the other still succeeds — no transaction between MongoDB and ES.

---

## Important differences from Spring Boot services

| Feature              | Spring Boot services     | audit-write-native (Quarkus)        |
|----------------------|--------------------------|--------------------------------------|
| Health endpoint      | GET /actuator/health     | GET /q/health                        |
| Config source        | config-server + dev yml  | src/main/resources/application.properties |
| Service registry     | Eureka auto-registration | NOT registered in Eureka             |
| Kafka framework      | Spring Kafka             | SmallRye Reactive Messaging          |
| Build                | mvn spring-boot:run      | mvn quarkus:dev OR native binary     |
| Dockerfile location  | ./Dockerfile             | src/main/docker/Dockerfile.native    |

---

## Notes

- Elasticsearch host in application.properties: `http://localhost:9201` — host port is 9202, may need fix
- Not in Spring Cloud Bus — config changes require restart
- Not in Eureka — gateway cannot load-balance to it; must use direct URL or fixed port
- kafkaOffset = -1 in some documents is a known cosmetic issue (non-blocking)
