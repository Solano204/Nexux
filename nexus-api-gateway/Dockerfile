# ══════════════════════════════════════════════════════════════
# Multi-stage build for nexus-api-gateway
# Stage 1: Build the application
# Stage 2: Minimal runtime image
# ══════════════════════════════════════════════════════════════

# Stage 1 — Build
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and POM first (layer caching)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN ./mvnw package -DskipTests -q \
    && ls -la target/*.jar

# Stage 2 — Runtime
FROM eclipse-temurin:25-jre-alpine AS runtime

# Security: run as non-root user
RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus

WORKDIR /app

# Copy the JAR from builder stage
COPY --from=builder --chown=nexus:nexus \
    /build/target/nexus-api-gateway-*.jar \
    app.jar

# Health check using the actuator endpoint
HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=60s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

# JVM tuning for gateway workload:
# - ZGC: sub-millisecond GC pauses (latency-critical gateway)
# - Small heap: gateway does minimal computation
# - Virtual Threads: enabled via Spring Boot auto-configuration
# - Entropy: /dev/urandom prevents JVM blocking on crypto operations
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-Xms256m", \
    "-Xmx512m", \
    "-XX:MaxMetaspaceSize=128m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", \
    "-jar", "app.jar"]