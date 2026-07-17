FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus
WORKDIR /app

COPY --chown=nexus:nexus target/nexus-discovery-service-1.0.0.jar app.jar
COPY --chown=nexus:nexus --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

HEALTHCHECK \
    --interval=15s \
    --timeout=10s \
    --start-period=90s \
    --retries=5 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8761/actuator/health || exit 1

EXPOSE 8761

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", \
    "-XX:+UseZGC", \
    "-Xms64m", \
    "-Xmx256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "--enable-preview", \
    "-jar", "app.jar"]