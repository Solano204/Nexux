---
name: build-all
description: Build all 16 NEXUS microservices with Maven, skipping tests, from the project root
---

Build all NEXUS microservices using the parent pom.xml.

Steps:
1. Verify you are in the project root (where pom.xml exists).
2. Run the following Maven command:
```bash
mvn clean package -DskipTests -pl nexus-config-service,nexus-discovery-service,nexus-identity-service,nexus-account-service,nexus-transaction-service,nexus-fraud-service,nexus-ledger-service,nexus-notification-service,nexus-ai-assistant-service,nexus-ai-kyc-service,nexus-analytics-service,nexus-risk-scoring-service,nexus-saga-orchestrator,nexus-audit-query-jvm,nexus-api-gateway --also-make
```
3. For the Quarkus native service (audit-write-native), build separately:
```bash
cd audit-write-native && mvn clean package -DskipTests -Pjvm && cd ..
```
4. Report which services built successfully and which failed, with the error summary per service.
5. If a service fails, show only the first ERROR line from its build output — do not dump the full log.

Notes:
- Requires Java 21 (`java -version` to verify)
- Requires Maven 3.9+ (`mvn --version` to verify)
- Target JARs land in `<service>/target/<service>-*.jar`
- Quarkus JVM output: `audit-write-native/target/quarkus-app/`
