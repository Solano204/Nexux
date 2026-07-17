FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus
WORKDIR /app

COPY --chown=nexus:nexus target/nexus-fraud-service-1.0.0.jar app.jar
COPY --chown=nexus:nexus --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8087/actuator/health || exit 1

EXPOSE 8087

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxGCPauseMillis=5", \
    "-Xms512m", \
    "-Xmx1g", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.classformat.ignore=true", \
    "--enable-preview", \
    "-jar", "app.jar"]
