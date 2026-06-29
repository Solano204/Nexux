FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S nexus && adduser -S nexus -G nexus
USER nexus
WORKDIR /app

COPY --chown=nexus:nexus target/nexus-risk-scoring-service-1.0.0.jar app.jar

HEALTHCHECK --interval=30s --timeout=10s \
    --start-period=60s --retries=3 \
    CMD wget -q --spider \
        http://localhost:8094/actuator/health || exit 1

EXPOSE 8094

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxGCPauseMillis=20", \
    "-XX:ZUncommitDelay=300", \
    "-Xms512m", \
    "-Xmx1536m", \
    "-XX:MaxMetaspaceSize=256m", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.classformat.ignore=true", \
    "-Dspring.ai.openai.chat.options.timeout=120s", \
    "--enable-preview", \
    "-jar", "app.jar"]
