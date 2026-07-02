# nexus-discovery-service — Complete Dependency Map

## Port: 8761  |  Must start SECOND (after config service)

---

## Infrastructure REQUIRES (direct)

None — Eureka is self-contained.

**NO PostgreSQL, NO MongoDB, NO Redis, NO Elasticsearch, NO Kafka, NO S3, NO SQS, NO OpenAI**

---

## What discovery service does

All 14 other services register with Eureka on startup:
```
Service starts → POST http://localhost:8761/eureka/apps/{SERVICE-NAME}
             → Eureka stores instance info (host, port, health URL)
             → API Gateway resolves lb://nexus-account-service → localhost:8085
             → Services heartbeat every 30s
             → Eureka removes instance after 90s without heartbeat
```

---

## Registered services (all register here)

| Service name in Eureka          | Port |
|---------------------------------|------|
| NEXUS-API-GATEWAY               | 8080 |
| NEXUS-IDENTITY-SERVICE          | 8010 |
| NEXUS-ACCOUNT-SERVICE           | 8085 |
| NEXUS-TRANSACTION-SERVICE       | 8086 |
| NEXUS-FRAUD-SERVICE             | 8087 |
| NEXUS-LEDGER-SERVICE            | 8088 |
| NEXUS-NOTIFICATION-SERVICE      | 8089 |
| NEXUS-AI-ASSISTANT-SERVICE      | 8090 |
| NEXUS-AI-KYC-SERVICE            | 8091 |
| NEXUS-ANALYTICS-SERVICE         | 8092 |
| NEXUS-RISK-SCORING-SERVICE      | 8094 |
| NEXUS-SAGA-ORCHESTRATOR         | 8095 |
| NEXUS-AUDIT-QUERY-JVM           | 8097 |

NOT registered: audit-write-native (Quarkus — different framework), config-service (register-with-eureka: false)

---

## Endpoints

| Path                        | What it does                                  |
|-----------------------------|-----------------------------------------------|
| GET http://localhost:8761/  | Eureka dashboard UI                           |
| GET /eureka/apps            | all registered instances (XML)               |
| GET /actuator/health        | health check                                  |

---

## Notes

- No auth on Eureka UI/API in dev — open access
- If discovery goes down, services cannot resolve lb:// URIs → all inter-service calls via gateway fail
- Startup order: config-service → discovery-service → all others
