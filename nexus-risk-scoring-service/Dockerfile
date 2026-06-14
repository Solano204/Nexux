# ══════════════════════════════════════════════════════════════
# nexus-risk-scoring-service — Dockerfile (FIXED)
#
# Fix from original:
#   Added -XX:+ZGenerational — required/recommended for Java 25 ZGC.
#   Without it, Java 25 uses deprecated non-generational ZGC.
#   Risk scoring runs a nightly batch over all users; the long-running
#   multi-hour job benefits from generational GC (better young-gen
#   throughput during the compute-heavy Plan-then-Act agent calls).
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
    /build/target/nexus-risk-scoring-service-*.jar app.jar

HEALTHCHECK --interval=30s --timeout=10s \
    --start-period=60s --retries=3 \
    CMD wget -q --spider \
        http://localhost:8094/actuator/health || exit 1

EXPOSE 8094

# JVM tuning for risk scoring service:
# - ZGC + ZGenerational: long-running nightly batch; generational GC
#   handles the high allocation rate during Plan-then-Act agent loops
# - 1.5GB heap: 8 AI tools build large context windows per user;
#   batch processes hundreds of users concurrently via Virtual Threads
# - ZUncommitDelay=300: release unused memory back to OS after 5 min
#   (important for a service that idles most of the day)
# - OpenAI timeout: 120s per risk scoring call (complex reasoning)
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-XX:MaxGCPauseMillis=20", \
    "-XX:ZUncommitDelay=300", \
    "-Xms512m", \
    "-Xmx1536m", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.ai.openai.chat.options.timeout=120s", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", \
    "-jar", "app.jar"]
