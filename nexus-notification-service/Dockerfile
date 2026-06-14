# ══════════════════════════════════════════════════════════════
# nexus-notification-service — Dockerfile
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
    /build/target/nexus-notification-service-*.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8089/actuator/health || exit 1

EXPOSE 8089

# JVM tuning for notification service:
# - ZGC: sub-10ms pauses (AI generation calls hold threads)
# - 768m heap: Spring AI JSON responses + MongoDB documents
# - Virtual Threads: enabled via application.yml (Kafka + AI calls are blocking)
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-Xms256m", \
    "-Xmx768m", \
    "-XX:MaxMetaspaceSize=192m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", \
    "-jar", "app.jar"]
