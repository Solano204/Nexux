FROM eclipse-temurin:25-jre-alpine

RUN mkdir -p /var/kafka-streams \
    && addgroup -S nexus \
    && adduser -S nexus -G nexus \
    && chown -R nexus:nexus /var/kafka-streams

USER nexus
WORKDIR /app

COPY --chown=nexus:nexus target/nexus-analytics-service-1.0.0.jar app.jar

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8092/actuator/health || exit 1

EXPOSE 8092

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxGCPauseMillis=10", \
    "-Xms1g", \
    "-Xmx2g", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Drocksdb.block.cache.size.mb=256", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.classformat.ignore=true", \
    "--enable-preview", \
    "-jar", "app.jar"]
