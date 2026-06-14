# ══════════════════════════════════════════════════════════════
# nexus-saga-orchestrator — Dockerfile (FIXED)
#
# Fixes from original:
#   1. Added -XX:+ZGenerational (required for Java 25 ZGC)
#   2. Increased heap: 512m → 768m (40 Hikari connections +
#      4 Kafka consumer groups + JSONB deserialization)
#   3. Added -XX:MaxMetaspaceSize (SAGA has many JPA entities
#      with hypersistence JSONB types and Spring AI classes)
#   4. Added -Djava.security.egd (faster startup)
#   5. Added -XX:MaxGCPauseMillis=5 (explicit pause target;
#      SAGA is on the critical transaction path)
# ══════════════════════════════════════════════════════════════

# Stage 1 — Build
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q

COPY src ./src
RUN ./mvnw package -DskipTests -q

# Stage 2 — Runtime
FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus
WORKDIR /app

COPY --from=builder --chown=nexus:nexus \
    /build/target/nexus-saga-orchestrator-*.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=60s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8095/actuator/health || exit 1

EXPOSE 8095

# JVM tuning for SAGA orchestrator:
# - ZGC 5ms: SAGA is on the critical transaction path;
#   GC pauses translate directly to saga step latency
# - 768m heap: 40 Hikari connections + 4 Kafka consumer groups
#   + JSONB deserialization + Spring AI context
# - ZGenerational: required/recommended for Java 25 ZGC
# - MaxMetaspaceSize: many JPA entities + hypersistence JSONB
# - Virtual Threads: Kafka consumer threads + AI call are blocking
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-XX:MaxGCPauseMillis=5", \
    "-Xms256m", \
    "-Xmx768m", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", \
    "-jar", "app.jar"]
