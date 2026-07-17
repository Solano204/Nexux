# Debian-based (glibc), not Alpine (musl): Kafka Streams pulls in RocksDB's
# native JNI library, which is built against glibc + libstdc++ and fails
# with UnsatisfiedLinkError ("Error loading shared library libstdc++.so.6")
# on musl-based Alpine. This is the only Nexus service using Kafka Streams,
# so it's the only Dockerfile that needs to differ from the platform's
# otherwise-uniform *-jre-alpine base.
FROM eclipse-temurin:25-jre-jammy

# Unlike Alpine (BusyBox bundles wget by default), Debian/Ubuntu-based images
# don't ship wget - the HEALTHCHECK below needs it explicitly installed, or
# the healthcheck command itself fails to execute and the container is
# permanently reported unhealthy regardless of the app's actual state.
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/kafka-streams \
    && groupadd -r nexus \
    && useradd -r -g nexus nexus \
    && chown -R nexus:nexus /var/kafka-streams

USER nexus
WORKDIR /app

COPY --chown=nexus:nexus target/nexus-analytics-service-1.0.0.jar app.jar
COPY --chown=nexus:nexus --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget --quiet --tries=1 --spider \
        http://localhost:8092/actuator/health || exit 1

EXPOSE 8092

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", \
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
