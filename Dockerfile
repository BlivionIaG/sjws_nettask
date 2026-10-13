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

FROM eclipse-temurin:25-jre
# curl for HEALTHCHECK (not guaranteed in the base image).
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
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
  CMD ["curl", "-fsS", "http://127.0.0.1:8080/healthz"]
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/opt/sjws/sjws_nettask.jar"]
CMD ["8080", "/srv/www", "--healthz"]
