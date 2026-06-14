# ══════════════════════════════════════════════════════════════
# nexus-discovery-service — Dockerfile (IMPROVED)
#
# Changes from original:
#   1. Added -XX:+UseZGC and -XX:+ZGenerational
#   2. Added -XX:MaxMetaspaceSize
#   3. Added -Djava.security.egd (faster startup — 13 services
#      are blocked waiting for this to become healthy)
#   4. Explicit --enable-preview (inherited from platform standard)
#   5. Heap kept small (256m max) — Eureka is in-memory but light
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
    /build/target/nexus-discovery-service-*.jar app.jar

# All 13 business services depend on this returning healthy.
# Keep interval tight — platform startup time depends on it.
HEALTHCHECK \
    --interval=10s \
    --timeout=5s \
    --start-period=20s \
    --retries=5 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8761/actuator/health || exit 1

EXPOSE 8761

# JVM tuning for discovery service:
# - Minimal heap: Eureka registry is in-memory, very light
# - Fast startup is the priority: 13 services wait on this
# - ZGenerational: Java 25 standard
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
