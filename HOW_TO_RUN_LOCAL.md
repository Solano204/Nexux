# HOW TO RUN NEXUS LOCALLY — DEV MODE

All services run on **localhost**. Infrastructure (Kafka, DBs, Redis, Zipkin…) runs in
Docker. Each service needs Java 25 set **per terminal** before running.

---

## STEP 0 — Start Docker Infrastructure (always first)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/kafka
docker compose up -d
```

Wait ~30 s for Kafka to become healthy, then verify:

```bash
docker compose ps
```

All containers should show **healthy** before starting any service.

---

## STEP 1 — Config Service (port 8888)

> Always start this first. All other services pull config from here.

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-config-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn dependency:copy-dependencies -DoutputDirectory=target/libs

mvn spring-boot:run -Dspring-boot.run.profiles=dev

```

Wait until you see: `Started NexusConfigServiceApplication`

---

## STEP 2 — Discovery / Eureka (port 8761)

> Start this second. All services register here.

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-discovery-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
mvn dependency:copy-dependencies -DoutputDirectory=target/libs
java --enable-preview \
     -Dspring.profiles.active=dev \
     -cp "target/classes;target/libs/*" \
     com.nexus.discovery.Main
```

Wait until Eureka dashboard is reachable: http://localhost:8761

---

## STEP 3 — Identity Service (port 8083)

> Required for JWT login and KYC. Start before the gateway.

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-identity-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests

mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## STEP 4 — API Gateway (port 8080)

> Start last among core services. Routes all external traffic.

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-api-gateway

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests

mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## BUSINESS SERVICES — Run only what you need

> Steps 0–4 must already be running. Open a new terminal for each service.

---

### Account Service (port 8085)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-account-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
 mvn spring-boot:run -Dspring-boot.run.profiles=dev
java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp "target/classes;target/libs/*" \
     com.nexus.account.AccountApplication
```

---

### Transaction Service (port 8086)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-transaction-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp "target/classes;target/libs/*" \
     com.nexus.transaction.TransactionApplication
```

---

### Fraud Service (port 8087)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-fraud-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp "target/classes;target/libs/*" \
     com.nexus.fraud.FraudApplication
```

---

### Ledger Service (port 8088)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-ledger-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp "target/classes;target/libs/*" \
     com.nexus.ledger.LedgerApplication
```

---

### Notification Service (port 8089)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-notification-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp "target/classes;target/libs/*" \
     com.nexus.notification.NotificationApplication
```
java --enable-preview \
-Dspring.classformat.ignore=true \
-Dspring.profiles.active=dev \
-Dspring.cloud.compatibility-verifier.enabled=false \
-Dspring.cloud.config.enabled=false \
-Dspring.config.import="" \
-Dspring.kafka.bootstrap-servers=localhost:19092 \
-Dspring.kafka.consumer.group-id=saga-orchestrator-debug \
-Dspring.kafka.consumer.enable-auto-commit=false \
-Dspring.kafka.consumer.auto-offset-reset=earliest \
-Dlogging.level.org.springframework.kafka=TRACE \
-Dlogging.level.org.apache.kafka=DEBUG \
-Dlogging.level.org.springframework.context=DEBUG \
-cp "target/classes;target/libs/*" \
com.nexus.notification.NotificationApplication 2>&1 | head -200
---

### AI Assistant Service (port 8090)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-ai-assistant-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -Dspring.autoconfigure.exclude=org.springframework.ai.autoconfigure.ollama.OllamaAutoConfiguration \
     -Dspring.cloud.config.username=nexus-config \
     -Dspring.cloud.config.password=nexus-config-password \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5433/nexus_ai_assistant \
     -Dspring.datasource.username=nexus \
     -Dspring.datasource.password=nexus_dev_password \
     -Dspring.data.redis.host=localhost \
     -Dspring.data.redis.port=6379 \
     -Dspring.kafka.bootstrap-servers=localhost:19092 \
     -Dspring.ai.openai.api-key=dummy \
     -cp "target/classes;target/libs/*" \
     com.nexus.assistant.AiAssistantApplication
```

---

### AI KYC Service (port 8091)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-ai-kyc-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
 mvn spring-boot:run -Dspring-boot.run.profiles=dev

java --enable-preview \
     -Dspring.profiles.active=dev \
     -Dspring.data.mongodb.uri=mongodb://localhost:27018/nexus_kyc \
     -cp "target/classes;target/libs/*" \
     com.nexus.kyc.AiKycApplication
```

---

### Analytics Service (port 8092)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-analytics-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp "target/classes;target/libs/*" \
     com.nexus.analytics.AnalyticsApplication
```

---

### Risk Scoring Service (port 8094)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-risk-scoring-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp "target/classes;target/libs/*" \
     com.nexus.risk.RiskScoringApplication
```

---

### Saga Orchestrator (port 8095)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-saga-orchestrator

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests

mvn spring-boot:run -Dspring-boot.run.profiles=dev


java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp "target/classes;target/libs/*" \
     com.nexus.saga.SagaOrchestratorApplication
```

---

### Audit Write Native — Quarkus (port 8096)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/audit-write-native

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn quarkus:dev
```

---

### Audit Query JVM (port 8097)

```bash
cd ~/Music/"PROJECT IA STARTUP"/NEXUS/nexus-audit-query-jvm

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev

java --enable-preview \
     -Dspring.classformat.ignore=true \
     -Dspring.profiles.active=dev \
     -Dspring.cloud.compatibility-verifier.enabled=false \
     -cp "target/classes;target/libs/*" \
     com.nexus.audit.query.AuditQueryApplication
```

---

## PORTS REFERENCE

### Infrastructure (Docker — always running)

| Container | Host Port | URL |
|---|---|---|
| PostgreSQL | 5433 | `jdbc:postgresql://localhost:5433` |
| MongoDB | 27018 | `mongodb://localhost:27018` |
| Redis | 6380 | `redis://localhost:6380` |
| Kafka | 19092 | `localhost:19092` |
| Elasticsearch | 9201 | http://localhost:9201 |
| Zipkin | 9412 | http://localhost:9412 |
| Prometheus | 9091 | http://localhost:9091 |
| Grafana | 3001 | http://localhost:3001 (admin / admin) |
| Kafka UI | 8190 | http://localhost:8190 |

### Services

| Service | Port | Health |
|---|---|---|
| Config Service | 8888 | http://localhost:8888/actuator/health |
| Discovery / Eureka | 8761 | http://localhost:8761 |
| Identity Service | 8083 | http://localhost:8083/actuator/health |
| API Gateway | 8080 | http://localhost:8080/actuator/health |
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
| Audit Write Native | 8096 | http://localhost:8096/q/health |
| Audit Query JVM | 8097 | http://localhost:8097/actuator/health |

---

## MINIMUM SETS PER FEATURE

| Goal | Services to run |
|---|---|
| Login / JWT only | identity |
| Account operations | identity + account |
| Full transaction flow | identity + account + transaction + fraud + ledger + saga |
| AI chat assistant | identity + account + transaction + fraud + ai-assistant |
| KYC pipeline | identity + ai-kyc |
| Audit trail | audit-write-native + audit-query-jvm |
| Notifications | notification |
| Analytics dashboards | analytics |

> **Steps 0–4** (infra + config + discovery + identity + gateway) are always required.
