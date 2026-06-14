# ══════════════════════════════════════════════════════════════
# nexus-audit-query-jvm — Dockerfile
# Multi-stage build: Java 25, ZGC, Virtual Threads
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
    /build/target/nexus-audit-query-jvm-*.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8097/actuator/health || exit 1

EXPOSE 8097

# JVM tuning for audit query service:
# - ZGC: compliance queries hold large Elasticsearch response payloads
#   in memory during AI processing — low GC pauses keep queries snappy
# - 1GB heap: AI context windows are large for compliance analysis
#   (multiple audit event documents + pgvector retrieval results)
# - Virtual Threads: Elasticsearch + MongoDB + OpenAI calls all blocking;
#   concurrent query execution via StructuredTaskScope (JEP 505)
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-XX:MaxGCPauseMillis=10", \
    "-Xms512m", \
    "-Xmx1g", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", \
    "-jar", "app.jar"]
