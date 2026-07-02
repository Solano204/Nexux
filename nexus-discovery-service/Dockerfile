# ── Build stage ───────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build

COPY pom.xml parent-pom.xml

RUN apk add --no-cache maven && \
    mvn install:install-file \
        -Dfile=parent-pom.xml \
        -DgroupId=com.nexus \
        -DartifactId=nexus-financial-platform \
        -Dversion=1.0.0 \
        -Dpackaging=pom

COPY nexus-discovery-service/pom.xml pom.xml
COPY nexus-discovery-service/src ./src

RUN mvn clean package -DskipTests

# ── Runtime stage ─────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus
WORKDIR /app
COPY --from=builder --chown=nexus:nexus /build/target/nexus-discovery-service-1.0.0.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "--enable-preview", "-XX:+UseZGC", "-Xms64m", "-Xmx256m", "-jar", "app.jar"]