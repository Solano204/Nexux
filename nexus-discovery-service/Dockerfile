FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q
COPY src ./src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus
WORKDIR /app
COPY --from=builder --chown=nexus:nexus \
    /build/target/nexus-discovery-service-*.jar app.jar

# ── Health check ───────────────────────────────────────────────
# All 13 business services depend on this returning healthy.
# Keep interval tight (10s) — platform startup waits on this.
HEALTHCHECK \
    --interval=10s \
    --timeout=5s \
    --start-period=20s \
    --retries=5 \
    CMD wget -q --spider \
        http://localhost:8761/actuator/health || exit 1

EXPOSE 8761

# Minimal heap — registry is in-memory, very light computation
ENTRYPOINT ["java", \
    "-Xms128m", "-Xmx256m", \
    "-Dspring.profiles.active=docker", \
    "-jar", "app.jar"]