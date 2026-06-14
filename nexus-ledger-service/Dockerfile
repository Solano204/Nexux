# ══════════════════════════════════════════════════════════════
# nexus-ledger-service — Dockerfile
# Multi-stage build: Java 25, ZGC, Virtual Threads
# NOTE: This file was missing from the repo — created from scratch.
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
    /build/target/nexus-ledger-service-*.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8088/actuator/health || exit 1

EXPOSE 8088

# JVM tuning for ledger service:
# - ZGC with 5ms pause target — SERIALIZABLE transactions hold locks;
#   GC pauses must not exceed PostgreSQL lock_timeout window
# - 1GB heap: double-entry postings generate many intermediate objects
# - Virtual Threads: enabled via application.yml (JPA + reconciliation jobs)
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-XX:MaxGCPauseMillis=5", \
    "-Xms512m", \
    "-Xmx1g", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", \
    "-jar", "app.jar"]
