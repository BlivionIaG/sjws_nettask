# syntax=docker/dockerfile:1
# Multi-stage image for sjws_nettask. See docs/deployment/docker.md.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
# Dependencies first so they stay cached until pom.xml changes.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package \
 && mkdir -p /out \
 && cp target/sjws_nettask-*.jar /out/sjws_nettask.jar \
 && cp -r target/lib /out/lib
# Health probe without curl in the runtime image (the JRE has no javac, so compile here).
COPY docker/HealthCheck.java .
RUN javac --release 25 -d /out/healthcheck HealthCheck.java

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 sjws \
 && useradd --system --uid 10001 --gid sjws --home-dir /opt/sjws --shell /usr/sbin/nologin sjws
WORKDIR /opt/sjws
# The jar's manifest Class-Path points at lib/ (Netty + QUIC natives) next to it.
COPY --from=build /out/ /opt/sjws/
COPY HTML/ /srv/www/
USER 10001:10001
# 8080/tcp HTTP/1.1 (and h2c); 8443 for TLS/HTTP/3 when --cert/--key are mounted and passed.
EXPOSE 8080/tcp 8443/tcp 8443/udp
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
  CMD ["java", "-Xshare:auto", "-XX:TieredStopAtLevel=1", "-Xmx16m", "-cp", "/opt/sjws/healthcheck", "HealthCheck", "http://127.0.0.1:8080/healthz"]
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/opt/sjws/sjws_nettask.jar"]
CMD ["8080", "/srv/www", "--healthz"]
