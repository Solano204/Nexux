# ══════════════════════════════════════════════════════════════
# nexus-config-service — Dockerfile (IMPROVED)
#
# Changes from original:
#   1. Added -XX:+ZGenerational (required for Java 25 ZGC)
#   2. Added -XX:MaxGCPauseMillis (consistent with platform default)
#   3. Added -XX:MaxMetaspaceSize (prevents unbounded metaspace)
#   4. Added -Djava.security.egd (faster startup)
#   5. Heap kept small (256m max) — config server does minimal work
#
# NOTE: git is already installed in the original Dockerfile.
# The config server uses git clone/pull for the file:// backend.
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

# Install git — required for the Git backend clone/pull operations
RUN apk add --no-cache git

RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus

WORKDIR /app

COPY --from=builder --chown=nexus:nexus \
    /build/target/nexus-config-service-*.jar app.jar

HEALTHCHECK \
    --interval=10s \
    --timeout=5s \
    --start-period=30s \
    --retries=5 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8888/actuator/health || exit 1

EXPOSE 8888

# JVM tuning for config service:
# - Minimal heap: config server clones/reads YAML files — not memory intensive
# - ZGC + ZGenerational: consistent with platform standard
# - Fast startup is the priority: other services fail-fast waiting for this
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-XX:MaxGCPauseMillis=10", \
    "-Xms64m", \
    "-Xmx256m", \
    "-XX:MaxMetaspaceSize=192m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", \
    "-jar", "app.jar"]
