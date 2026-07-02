# nexus-api-gateway — Complete Dependency & Run Guide

## 1. What you need INSTALLED on your machine

| Tool | Minimum version | Why |
|------|----------------|-----|
| Java (Temurin / Corretto) | **25** | `--enable-preview` and virtual threads |
| Maven | 3.9+ | Build tool |
| Docker Desktop (or Docker Engine) | 24+ with Compose v2 | Run all infrastructure |
| Git | any recent | Clone repo |

Check with:
```bash
java -version          # must say 25
mvn -version           # must be 3.9+
docker compose version # must be v2 (not docker-compose v1)
```

---

## 2. External services the gateway DEPENDS on at runtime

These must be running before the gateway starts. Docker Compose handles all of them automatically. If you run the gateway outside Docker (IDE / `mvn spring-boot:run -Pdev`), start them manually.

### 2.1 Always required

| Service | Container name | Default port | What it does for the gateway |
|---------|---------------|-------------|-------------------------------|
| **Redis 7.2** | `nexus-redis` | 6379 | JWT blacklist checks, rate limiting token buckets |
| **nexus-config-service** | `nexus-config-service` | 8888 | All config loaded from here at startup (`fail-fast: true` — gateway REFUSES to start without it) |
| **nexus-discovery-service** (Eureka) | `nexus-discovery-service` | 8761 | `lb://service-name` URI resolution for all routes |

### 2.2 Required for full functionality (not just startup)

| Service | Port | What breaks without it |
|---------|------|----------------------|
| **Kafka** | 9092 | Spring Cloud Bus config refresh; gateway starts but `POST /actuator/busrefresh` won't broadcast |
| **nexus-identity-service** | 8083 | JWT JWKS endpoint for signature validation. Gateway starts but every authenticated request returns 401 until JWKS loads |
| **nexus-zipkin** | 9411 | Distributed tracing. Gateway starts fine; traces just don't appear in Zipkin |
| **nexus-loki** | 3100 | Log shipping. Gateway starts fine; logs print to console but aren't in Grafana |

### 2.3 Downstream services (only needed for specific routes)

The gateway will start without these. Routes with circuit breakers will return fallback responses if the downstream is down.

| Service | Port | Routes |
|---------|------|--------|
| nexus-account-service | 8085 | `/api/v1/accounts/**` |
| nexus-transaction-service | 8086 | `/api/v1/transactions/**`, `/api/v1/webhooks/**` |
| nexus-fraud-service | 8087 | `/internal/v1/fraud/**` |
| nexus-ledger-service | 8088 | `/api/v1/ledger/**` |
| nexus-ai-assistant-service | 8090 | `/ai/**` |
| nexus-ai-kyc-service | 8091 | `/api/v1/kyc/**` |
| nexus-analytics-service | 8092 | `/api/v1/analytics/**` |
| nexus-risk-scoring-service | 8094 | `/api/v1/risk/**` |
| nexus-notification-service | 8089 | `/internal/v1/notifications/**` |

---

## 3. Maven dependencies in pom.xml — explained

Your `pom.xml` uses a parent POM (`nexus-financial-platform`). The parent manages all versions. Below is what the gateway uses and why.

### 3.1 Core gateway stack

```xml
<!-- Spring Cloud Gateway — the gateway itself (Netty-based, reactive) -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>

<!-- Eureka client — resolves lb://service-name URIs -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>

<!-- Config client — loads config from nexus-config-service at startup -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

### 3.2 Config refresh

```xml
<!-- Cloud Bus — broadcasts /actuator/busrefresh to all instances -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-bus</artifactId>
</dependency>
<!-- Kafka binder for the bus -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-stream-binder-kafka</artifactId>
</dependency>
```

### 3.3 Resilience

```xml
<!-- Circuit breaker integration for Spring Cloud Gateway -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId>
</dependency>
<!-- Resilience4j core + Spring Boot 3 autoconfiguration -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
<!-- Resilience4j Micrometer metrics (circuit breaker states as metrics) -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-micrometer</artifactId>
</dependency>
```

### 3.4 Redis (rate limiting + JWT blacklist)

```xml
<!-- Reactive Redis starter (Lettuce driver) — required for WebFlux gateway -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
<!-- Lettuce connection pool support -->
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
</dependency>
```

### 3.5 Security

```xml
<!-- Spring Security (WebFlux security config) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<!-- auth0 JWT library — RS256 validation -->
<dependency>
    <groupId>com.auth0</groupId>
    <artifactId>java-jwt</artifactId>
</dependency>
<!-- JWKS endpoint fetching + key rotation -->
<dependency>
    <groupId>com.auth0</groupId>
    <artifactId>jwks-rsa</artifactId>
</dependency>
```

### 3.6 Observability

```xml
<!-- Actuator — /actuator/health, /actuator/prometheus -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<!-- Prometheus registry — exposes Micrometer metrics in Prometheus format -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<!-- Micrometer Tracing — injects traceId/spanId into MDC (and logs) -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<!-- Zipkin reporter — sends spans to Zipkin server -->
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

### 3.7 Logging (NOT in pom.xml yet — you need to add these)

```xml
<!--
    LogstashEncoder — structured JSON log output.
    Required by logback-spring.xml for CONSOLE appender.
    Add to pom.xml dependencies:
-->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>

<!--
    Loki4j appender — ships logs to Grafana Loki.
    Required by logback-spring.xml for LOKI appender.
    Add to pom.xml dependencies:
-->
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>
```

> **Important:** Add these two to your `pom.xml` `<dependencies>` block. Your existing `logback-spring.xml` references both classes and will fail to compile/load without them.

### 3.8 Misc

```xml
<!-- Validation — @Valid on config classes -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<!-- Lombok — @Slf4j, @Getter etc. (compile-time only) -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <scope>provided</scope>
</dependency>
```

---

## 4. Environment variables reference

All variables with their defaults and whether they are required:

| Variable | Default | Required? | Description |
|----------|---------|-----------|-------------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** | `docker` for containers, `dev` for IDE |
| `REDIS_HOST` | `nexus-redis` | no | Redis hostname |
| `REDIS_PORT` | `6379` | no | Redis port |
| `REDIS_PASSWORD` | `""` | no (prod: YES) | Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no | Kafka brokers |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no | Eureka URL |
| `JWT_JWKS_URI` | `http://nexus-identity-service:8083/api/v1/auth/.well-known/jwks.json` | no | JWKS endpoint |
| `JWT_ISSUER` | `nexus-platform` | no | JWT issuer claim |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no | Zipkin collector |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no | Loki push URL |
| `TRACING_SAMPLE_RATE` | `1.0` (dev) / `0.1` (prod) | no | Fraction of traces to sample |
| `ENVIRONMENT` | `local` | no | Tag on all metrics |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | no (prod: YES) | CORS origins |
| `WEBHOOK_HMAC_SECRET` | `dev-hmac-secret-change-in-prod` | no (prod: YES) | HMAC secret for webhooks |

---

## 5. How to run

### 5a. Full Docker Compose (recommended — everything starts together)

```bash
# 1. Build the gateway JAR
cd nexus-api-gateway
mvn package -DskipTests

# 2. Start everything
cd ..   # back to monorepo root (or wherever docker-compose.yml is)
docker compose up -d

# 3. Watch logs
docker compose logs -f nexus-api-gateway

# 4. Check health
curl http://localhost:8080/actuator/health
```

### 5b. IDE / local Maven (dev profile)

Start Redis, Kafka, Config Service, and Discovery Service first (you can use Docker for just infrastructure):

```bash
# Start only infrastructure
docker compose up -d nexus-redis nexus-kafka nexus-config-service nexus-discovery-service

# Run gateway in IDE with:
#   VM options:  --enable-preview
#   Active profiles: dev

# OR from terminal:
cd nexus-api-gateway
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="--enable-preview" \
  -Dspring-boot.run.profiles=dev
```

### 5c. Run tests

```bash
cd nexus-api-gateway

# Unit tests only (no Docker needed)
mvn test -Dgroups="unit" --no-transfer-progress

# Integration tests (Docker required for Testcontainers)
mvn test -Dgroups="integration" --no-transfer-progress

# All tests
mvn test --no-transfer-progress
```

---

## 6. Startup order

The gateway requires services in this exact order:

```
1. nexus-redis            (fast, ~5s)
2. nexus-config-service   (loads config — ~20-30s)
3. nexus-discovery-service (registers with eureka — ~20-30s)
4. nexus-kafka            (optional but needed for bus refresh)
5. nexus-api-gateway      (loads config from #2, registers with #3 — ~60-90s)
```

Docker Compose healthchecks enforce this order automatically.

---

## 7. Observability URLs (after `docker compose up`)

| Dashboard | URL | Notes |
|-----------|-----|-------|
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | Raw metrics |
| Zipkin | http://localhost:9411 | Distributed traces |
| Eureka | http://localhost:8761 | Service registry |
| Config | http://localhost:8888/nexus-api-gateway/docker | Current config |
| Gateway health | http://localhost:8080/actuator/health | |
| Gateway metrics | http://localhost:8080/actuator/prometheus | Prometheus scrape endpoint |
| Gateway routes | http://localhost:8080/actuator/gateway/routes | All active routes |

---

## 8. Common startup problems and fixes

### "Could not fetch config from Config Service"
The gateway has `fail-fast: true`. It retries 10 times then exits.
**Fix:** Make sure `nexus-config-service` is healthy before the gateway starts.
```bash
docker compose up -d nexus-config-service
# Wait for it to be healthy:
docker compose ps nexus-config-service
# Then start the gateway:
docker compose up -d nexus-api-gateway
```

### "Connection refused: nexus-redis:6379"
Redis is not up yet.
**Fix:** `docker compose up -d nexus-redis` first, check `docker compose ps nexus-redis`.

### "No instances available for nexus-identity-service"
The Identity Service is not registered in Eureka. The gateway starts fine but JWT validation returns an error because it cannot load JWKS.
**Fix:** Start `nexus-identity-service`. The JWKS cache retries automatically on next request.

### "Could not enable preview features — add --enable-preview"
The JAR was built without `--enable-preview` or the JVM is not Java 25.
**Fix:** Confirm Java 25 is active: `java -version`. The Dockerfile already passes `--enable-preview` in `ENTRYPOINT`. For IDE runs, add `--enable-preview` to VM options.

### LogstashEncoder / Loki4jAppender ClassNotFoundException
The two logging dependencies are not in `pom.xml`.
**Fix:** Add them (see section 3.7 above).
