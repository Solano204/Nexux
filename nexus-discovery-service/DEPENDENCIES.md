# nexus-discovery-service — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

One bug that prevents startup — fix it before running.

---

## 1. What was created / replaced

| Item | Status |
|------|--------|
| `Dockerfile` | **IMPROVED** — added ZGC + ZGenerational, MaxMetaspaceSize, security.egd |
| `logback-spring.xml` | **IMPROVED** — added Loki + ASYNC_LOKI (neverBlock), plain-text dev profile, customFields (version/environment), production profile |
| `.github/workflows/nexus-discovery-service.yml` | **NEW** — no CI existed |

---

## 2. No database, no Kafka, no AI

This service has zero external dependencies at runtime. The in-memory Eureka registry holds all state. If the container restarts, all services re-register automatically within 30 seconds via their heartbeat cycle.

---

## 3. Startup order

```
1. nexus-config-service    (port 8888)  ← discovery fetches its config here
2. nexus-discovery-service (port 8761)  ← ALL other services wait on this
3. [all 13 business services]
```

Discovery itself uses `fail-fast: false` for the config server — it tolerates config server being slow/unavailable at startup and uses its own `application.yml` as fallback. This is intentional: someone has to be first.

---

## 4. Missing dependency — add to pom.xml

```xml
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>
```

---

## 5. Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | — | `docker` or `production` |
| `CONFIG_SERVER_URI` | `http://nexus-config-service:8888` | Config server URL |
| `CONFIG_SERVER_USERNAME` | `nexus-config` | Basic auth for config server |
| `CONFIG_SERVER_PASSWORD` | `nexus-config-pass` | Change in production |
| `EUREKA_USERNAME` | `eureka-admin` | Eureka dashboard basic auth |
| `EUREKA_PASSWORD` | `eureka-admin-password` | Change in production |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | Log shipping |

---

## 6. How to run

```bash
# 1. Fix Main.java (see BUGS.md)
# 2. Build
mvn package -DskipTests
# 3. Start
docker compose up -d
# 4. Verify
curl http://localhost:8761/actuator/health
# 5. See registered services (after others start)
curl http://localhost:8761/eureka/apps
```

---

## 7. Production note — self-preservation

`application.yml` has `enable-self-preservation: false` (dev setting). In production with multiple services, enable it to prevent mass deregistration during network partitions:

```yaml
eureka:
  server:
    enable-self-preservation: true
    renewal-percent-threshold: 0.85
```

Or override via environment variable in `docker-compose.prod.yml`.
