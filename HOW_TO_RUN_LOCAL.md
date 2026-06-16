# How to Run NEXUS Services Locally (dev profile)

All services run on **localhost**. External dependencies (Kafka, DBs, Redis, Zipkin)
run in Docker. Business services run as local JVM processes.

---

## Step 0 — Start Docker infra (always first)

```bash
cd kafka
docker compose up -d
```

Wait until Kafka is healthy before starting any service (~30s).
Check: `docker compose ps`

---

## Step 1 — Config Service (port 8888)

```bash
cd nexus-config-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Wait until you see: `Started NexusConfigServiceApplication`

---

## Step 2 — Discovery / Eureka (port 8761)

The discovery service uses a custom classpath (Java 25 + preview features).
Build once, then run:

```bash
cd nexus-discovery-service

# Build first (only needed after code changes)
mvn package -DskipTests

# Run
java --enable-preview \
  -Dspring.profiles.active=dev \
  -cp "target/classes;$(cat classpath.txt)" \
  com.nexus.discovery.Main
```

> On Windows Git Bash use the semicolon separator as shown.
> Wait until you see: `Started Main` and Eureka dashboard at http://localhost:8761

---

## Step 3 — Identity Service (port 8083)

```bash
cd nexus-identity-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## Step 4 — API Gateway (port 8080)

```bash
cd nexus-api-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## Business Services (any order, any subset)

Run only the ones you need. See `INDEPENDENT_SERVICES.md` for which ones
you actually need per feature.

### Account Service (port 8085)
```



cd nexus-account-service
# 1. Forzar la ruta física real del JDK 25
export JAVA_HOME="C:/Program Files/Java/jdk-25"

# 2. Correr el Config Server en modo desarrollo
mvn spring-boot:run -Dspring-boot.run.profiles=dev


```

### Transaction Service (port 8086)
Step 2 — Rebuild with JAVA_HOME now correct
bash
export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
Step 3 — Verify libs folder was actually created
bashls target/libs/ | wc -l
# Should print 100+
If it prints 0 or errors, the plugin isn't wired correctly.
Step 4 — Run
java --enable-preview \
-Dspring.classformat.ignore=true \
-Dspring.profiles.active=dev \
-Dspring.cloud.compatibility-verifier.enabled=false \
-cp 'target/classes;target/libs/*' \
com.nexus.transaction.TransactionApplication

Important note on JAVA_HOME persistence
Every time you open a new Git Bash terminal, JAVA_HOME resets. To make it permanent, add these two lines to ~/.bashrc:
bashecho 'export JAVA_HOME="/c/Program Files/Java/jdk-25"' >> ~/.bashrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc

```

### Fraud Service (port 8087)
```


export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests

java --enable-preview \
-Dspring.classformat.ignore=true \
-Dspring.profiles.active=dev \
-Dspring.cloud.compatibility-verifier.enabled=false \
-cp 'target/classes;target/libs/*' \
com.nexus.fraud.FraudApplication

cd nexus-fraud-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
`
cd ~/Music/PROJECT\ IA\ STARTUP/NEXUS/nexus-ledger-service

# Set JAVA_HOME if new terminal
export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

# Confirm main class exists
find src -name "*Application.java"

mvn clean package -DskipTests

# Verify libs were copied
ls target/libs/ | wc -l   # expect 100+

# Run
java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp 'target/classes;target/libs/*' \
     com.nexus.ledger.LedgerApplication
     
### Ledger Service (port 8088)
```bash
cd nexus-ledger-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Notification Service (port 8089)
```bash
cd nexus-notification-
mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd ~/Music/PROJECT\ IA\ STARTUP/NEXUS/nexus-notification-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests

ls target/libs/ | wc -l   # expect 100+

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -Dspring.cloud.config.username=nexus-config \
     -Dspring.cloud.config.password=nexus-config-password \
     -Dspring.data.mongodb.uri=mongodb://nexus:nexus_dev_password@localhost:27018/nexus_notification?authSource=admin \
     -Dspring.data.redis.host=localhost \
     -Dspring.data.redis.port=6379 \
     -Dspring.data.redis.password= \
     -Dspring.kafka.bootstrap-servers=localhost:19092 \
     -Dspring.ai.openai.api-key=dummy \
     -cp 'target/classes;target/libs/*' \
     com.nexus.notification.NotificationApplication
```

### AI Assistant Service (port 8090)
```bash
cd nexus-ai-assistant-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev


cd ~/Music/PROJECT\ IA\ STARTUP/NEXUS/nexus-ai-assistant-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests

ls target/libs/ | wc -l



java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -Dspring.cloud.config.username=nexus-config \
     -Dspring.cloud.config.password=nexus-config-password \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5433/nexus_ai_assistant \
     -Dspring.datasource.username=nexus \
     -Dspring.datasource.password=nexus_dev_password \
     -Dspring.data.redis.host=localhost \
     -Dspring.data.redis.port=6379 \
     -Dspring.data.redis.password= \
     -Dspring.kafka.bootstrap-servers=localhost:19092 \
     -Dspring.ai.openai.api-key=dummy \
     -Dspring.ai.ollama.base-url=http://localhost:11434 \
     -Dspring.jpa.hibernate.ddl-auto=update \
     -cp 'target/classes;target/libs/*' \
     com.nexus.assistant.AiAssistantApplication
```

### AI KYC Service (port 8091)
```bash
cd nexus-ai-kyc-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev

cd ~/Music/PROJECT\ IA\ STARTUP/NEXUS/nexus-ai-kyc-service

find src/main/resources -name "*.yml" -o -name "*.properties"
cat src/main/resources/application.yml

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
ls target/libs/ | wc -l
java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -Dspring.cloud.config.username=nexus-config \
     -Dspring.cloud.config.password=nexus-config-password \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5433/nexus_kyc \
     -Dspring.datasource.username=nexus \
     -Dspring.datasource.password=nexus_dev_password \
     -Dspring.data.mongodb.uri=mongodb://localhost:27018/nexus_kyc \
     -Dspring.kafka.bootstrap-servers=localhost:19092 \
     -Dspring.ai.openai.api-key=dummy \
     -cp 'target/classes;target/libs/*' \
     com.nexus.kyc.AiKycApplication
```

### Analytics Service (port 8092)
```bash
cd nexus-analytics-service
mvn spring-boot:run -Dspring-boot.run.profiles=



export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
ls target/libs/ | wc -l



```

### Risk Scoring Service (port 8094)
```bash
cd nexus-risk-scoring-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests

ls target/libs/ | wc -l   # expect 100+


java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -Dspring.cloud.config.username=nexus-config \
     -Dspring.cloud.config.password=nexus-config-password \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5433/nexus_risk \
     -Dspring.datasource.username=nexus \
     -Dspring.datasource.password=nexus_dev_password \
     -Dspring.data.redis.host=localhost \
     -Dspring.data.redis.port=6379 \
     -Dspring.data.redis.password= \
     -Dspring.kafka.bootstrap-servers=localhost:19092 \
     -Dspring.ai.openai.api-key=dummy \
     -cp 'target/classes;target/libs/*' \
     com.nexus.risk.RiskScoringApplication
     
     
```

### Saga Orchestrator (port 8095)
```bash
cd nexus-saga-orchestrator
mvn spring-boot:run -Dspring-boot.run.profiles=dev
mvn clean package -DskipTests

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -Dspring.cloud.config.username=nexus-config \
     -Dspring.cloud.config.password=nexus-config-password \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5433/nexus_saga \
     -Dspring.datasource.username=nexus \
     -Dspring.datasource.password=nexus_dev_password \
     -Dspring.kafka.bootstrap-servers=localhost:19092 \
     -Dspring.ai.openai.api-key=dummy \
     -Dspring.flyway.repair-on-migrate=true \
     -cp 'target/classes;target/libs/*' \
     com.nexus.saga.SagaOrchestratorApplication
```

### Audit Query JVM (port 8097)
```bash
cd nexus-audit-query-jvm
mvn quarkus:dev
```
cd ~/Music/PROJECT\ IA\ STARTUP/NEXUS/nexus-audit-query-jvm

find src/main/resources -name "*.yml" -o -name "*.properties"
cat src/main/resources/application.yml

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
ls target/libs/ | wc -l---
java --enable-preview \
-Dspring.classformat.ignore=true \
-Dspring.profiles.active=dev \
-Dspring.cloud.compatibility-verifier.enabled=false \
-Dspring.cloud.config.username=nexus-config \
-Dspring.cloud.config.password=nexus-config-password \
-Dspring.datasource.url=jdbc:postgresql://localhost:5433/nexus_audit \
-Dspring.datasource.username=nexus \
-Dspring.datasource.password=nexus_dev_password \
-Dspring.data.elasticsearch.uris=http://localhost:9200 \
-Dspring.data.mongodb.uri=mongodb://localhost:27018/nexus_audit \
-Dspring.ai.openai.api-key=dummy \
-Dspring.jpa.hibernate.ddl-auto=update \
-cp 'target/classes;target/libs/*' \
com.nexus.audit.query.AuditQueryApplication
## Quick Reference — Ports

| Service | Port | URL |
|---|---|---|
| Config Service | 8888 | http://localhost:8888/actuator/health |
| Eureka (Discovery) | 8761 | http://localhost:8761 |
| API Gateway | 8080 | http://localhost:8080/actuator/health |
| Identity Service | 8083 | http://localhost:8083/actuator/health |
| Account Service | 8085 | http://localhost:8085/actuator/health |
| Transaction Service | 8086 | http://localhost:8086/actuator/health |
| Fraud Service | 8087 | http://localhost:8087/actuator/health |
| Ledger Service | 8088 | http://localhost:8088/actuator/health |
| Notification Service | 8089 | http://localhost:8089/actuator/health |
| AI Assistant Service | 8090 | http://localhost:8090/actuator/health |
| AI KYC Service | 8091 | http://localhost:8091/actuator/health |
| Analytics Service | 8092 | http://localhost:8092/actuator/health |
| Risk Scoring Service | 8094 | http://localhost:8094/actuator/health |
| Saga Orchestrator | 8095 | http://localhost:8095/actuator/health |
| Audit Query JVM | 8097 | http://localhost:8097/q/health |
| Kafka UI | 8190 | http://localhost:8190 |
| Zipkin | 9411 | http://localhost:9411 |
| Prometheus | 9090 | http://localhost:9090 |
| Grafana | 3001 | http://localhost:3001  (admin / admin) |
| Elasticsearch | 9200 | http://localhost:9200 |
| PostgreSQL | 5432 | — |
| MongoDB | 27017 | — |
| Redis | 6379 | — |

---

## Minimum sets to test each feature

```
Login / JWT only          → identity-service
Account operations        → identity + account
Full transaction flow     → identity + account + transaction + fraud + ledger + saga-orchestrator
AI chat assistant         → identity + account + transaction + fraud + ai-assistant
KYC pipeline              → identity + ai-kyc
Notifications             → notification
Analytics dashboards      → analytics
```

Config service (:8888) + Eureka (:8761) + Docker infra are always required.
