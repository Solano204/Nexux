FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S nexus && adduser -S nexus -G nexus
RUN mkdir -p /app/keys && chown nexus:nexus /app/keys

USER nexus
WORKDIR /app

COPY --chown=nexus:nexus target/nexus-identity-service-1.0.0.jar app.jar
COPY --chown=nexus:nexus --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8083/actuator/health || exit 1

EXPOSE 8083

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", \
    "-XX:+UseZGC", \
    
    "-XX:MaxGCPauseMillis=10", \
    "-Xms512m", \
    "-Xmx1g", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "--enable-preview", \
    "-jar", "app.jar"]
