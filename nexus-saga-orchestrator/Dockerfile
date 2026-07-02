FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus
WORKDIR /app

COPY --chown=nexus:nexus target/nexus-saga-orchestrator-1.0.0.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=60s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8095/actuator/health || exit 1

EXPOSE 8095

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxGCPauseMillis=5", \
    "-Xms256m", \
    "-Xmx768m", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.classformat.ignore=true", \
    "--enable-preview", \
    "-jar", "app.jar"]
