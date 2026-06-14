# ══════════════════════════════════════════════════════════════
# nexus-ai-kyc-service — Dockerfile (IMPROVED)
#
# Changes from original:
#   1. Added -XX:+ZGenerational (required for ZGC in Java 25)
#   2. Added -XX:MaxMetaspaceSize (prevents uncapped metaspace
#      when loading large base64 document images into memory)
#   3. Added -Djava.security.egd (faster SecureRandom init;
#      important since AES-256 encryption uses SecureRandom)
#   4. Increased heap from 768m to 1g — GPT-4o base64-encodes
#      the entire document image in the request payload; 10MB
#      image ≈ 13MB base64 string held in memory during Stage 1
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
    /build/target/nexus-ai-kyc-service-*.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=60s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8091/actuator/health || exit 1

EXPOSE 8091

# JVM tuning for KYC service:
# - ZGC: document image payloads are large; GC pauses add to P99 latency
# - 1GB heap: 10MB doc image → 13MB base64 + Spring AI JSON context
# - ZGenerational: required for ZGC in Java 25
# - MaxMetaspaceSize: KYC pipeline uses multiple AI clients + GridFS
# - security.egd: faster SecureRandom for AES-256 document encryption
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
