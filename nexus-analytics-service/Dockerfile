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
    /build/target/nexus-analytics-service-*.jar app.jar
RUN mkdir -p /var/kafka-streams
HEALTHCHECK --interval=30s --timeout=10s \
    --start-period=90s --retries=3 \
    CMD wget -q --spider \
        http://localhost:8092/actuator/health || exit 1
EXPOSE 8092
ENTRYPOINT ["java", "-XX:+UseZGC", \
    "-XX:MaxGCPauseMillis=10", \
    "-Xms1g", "-Xmx2g", \
    "-Drockdb.block.cache.size.mb=256", \
    "-Dspring.profiles.active=docker", \
    "--enable-preview", "-jar", "app.jar"]