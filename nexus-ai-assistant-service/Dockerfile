FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus
WORKDIR /app

COPY --chown=nexus:nexus target/nexus-ai-assistant-service-1.0.0.jar app.jar
COPY --chown=nexus:nexus --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

HEALTHCHECK \
    --interval=30s \
    --timeout=15s \
    --start-period=120s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8090/actuator/health || exit 1

EXPOSE 8090

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxGCPauseMillis=10", \
    "-Xms512m", \
    "-Xmx1536m", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.classformat.ignore=true", \
    "--enable-preview", \
    "-jar", "app.jar"]
