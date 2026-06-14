# ══════════════════════════════════════════════════════════════
# nexus-analytics-service — Dockerfile (FIXED)
#
# Fixes from original:
#   1. mkdir /var/kafka-streams BEFORE USER nexus switch — the
#      original ran mkdir after USER nexus, causing runtime
#      permission denied when Kafka Streams writes RocksDB state.
#   2. Added -XX:+ZGenerational (required for ZGC in Java 25)
#   3. Added -XX:MaxMetaspaceSize (prevents uncapped metaspace growth
#      when 6 Streams topologies register many serdes and processors)
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

# Create the kafka-streams state directory as root BEFORE switching users
# so the nexus user has full write access at runtime.
RUN mkdir -p /var/kafka-streams \
    && addgroup -S nexus \
    && adduser -S nexus -G nexus \
    && chown -R nexus:nexus /var/kafka-streams

USER nexus
WORKDIR /app

COPY --from=builder --chown=nexus:nexus \
    /build/target/nexus-analytics-service-*.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8092/actuator/health || exit 1

EXPOSE 8092

# JVM tuning for analytics service:
# - ZGC: sub-10ms pauses — 6 Streams topologies + insight generation
# - 2GB heap: RocksDB block cache (256MB) + 4 stream threads + AI context
# - ZGenerational: required for ZGC in Java 25
# - Virtual Threads: enabled via application.yml
# - rocksdb.block.cache.size.mb: matches application.yml setting
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-XX:MaxGCPauseMillis=10", \
    "-Xms1g", \
    "-Xmx2g", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Drocksdb.block.cache.size.mb=256", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", \
    "-jar", "app.jar"]
