# ══════════════════════════════════════════════════════════════
# nexus-ai-assistant-service — Dockerfile
# Multi-stage build: Java 25, ZGC, Virtual Threads + WebFlux SSE
# NOTE: Dockerfile was missing from the repo — created from scratch.
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
    /build/target/nexus-ai-assistant-service-*.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=15s \
    --start-period=120s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8090/actuator/health || exit 1

EXPOSE 8090

# JVM tuning for AI assistant:
# - ZGC: sub-10ms pauses — SSE streaming clients notice any GC pause
# - 1.5GB heap: Spring AI builds large message lists + JSON parsing
# - Virtual Threads: blocking WebClient calls to account/transaction/fraud
#   services are transparent on virtual threads
# - Netty reactor threads + virtual threads coexist with WebFlux
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-XX:MaxGCPauseMillis=10", \
    "-Xms512m", \
    "-Xmx1536m", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", \
    "-jar", "app.jar"]
