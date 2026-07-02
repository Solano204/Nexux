FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache git

RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus
WORKDIR /app

COPY --chown=nexus:nexus target/nexus-config-service-1.0.0.jar app.jar

HEALTHCHECK \
    --interval=10s \
    --timeout=5s \
    --start-period=30s \
    --retries=5 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8888/actuator/health || exit 1

EXPOSE 8888

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    
    "-XX:MaxGCPauseMillis=10", \
    "-Xms64m", \
    "-Xmx256m", \
    "-XX:MaxMetaspaceSize=192m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "--enable-preview", \
    "-jar", "app.jar"]
