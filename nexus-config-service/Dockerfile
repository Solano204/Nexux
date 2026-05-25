FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q
COPY src ./src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:25-jre-alpine AS runtime

# Install git — required for the Git backend clone/pull operations
RUN apk add --no-cache git

RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus

WORKDIR /app
COPY --from=builder --chown=nexus:nexus \
    /build/target/nexus-config-service-*.jar app.jar

# Health check: verify config server is serving configuration
HEALTHCHECK \
    --interval=10s \
    --timeout=5s \
    --start-period=30s \
    --retries=5 \
    CMD wget -q --spider \
        http://localhost:8888/actuator/health || exit 1

EXPOSE 8888

# Minimal heap — config server does minimal computation
ENTRYPOINT ["java", \
    "-Xms128m", "-Xmx256m", \
    "-Dspring.profiles.active=docker", \
    "-jar", "app.jar"]