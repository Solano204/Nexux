# ══════════════════════════════════════════════════════════════
# Nexus Identity Service — JVM version
# Spring Boot 4, Java 25, Virtual Threads, Full AI integration
# ══════════════════════════════════════════════════════════════

FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q

COPY src ./src
RUN ./mvnw package -DskipTests -q

# ─── Runtime stage ───────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S nexus && adduser -S nexus -G nexus

# Create directory for JWT KeyStore volume mount
RUN mkdir -p /app/keys && chown nexus:nexus /app/keys

USER nexus
WORKDIR /app

COPY --from=builder --chown=nexus:nexus \
    /build/target/nexus-identity-service-*.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8083/actuator/health || exit 1

EXPOSE 8083

# JVM tuning for identity service:
# - ZGC: sub-10ms GC pauses (BCrypt + JPA are memory-allocating)
# - 1GB heap: Spring AI responses + JPA L1 cache + BCrypt
# - Virtual Threads: enabled via application.yml
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