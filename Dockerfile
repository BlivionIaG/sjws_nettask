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
# Only the Linux QUIC natives are usable here; macOS/Windows jars just add weight.
RUN javac --release 25 -d /out/healthcheck HealthCheck.java \
 && rm -f /out/lib/netty-codec-native-quic-*-osx-*.jar /out/lib/netty-codec-native-quic-*-windows-*.jar
# Minimal Java runtime. Modules from `jdeps --print-module-deps` over the jar and lib/
# (java.desktop/java.sql/java.management/jdk.jfr are pulled in by dependencies), plus the
# ones jdeps can't see: jdk.crypto.ec/jdk.crypto.cryptoki (TLS/ACME keys), jdk.naming.dns
# (Netty/JNDI DNS), jdk.zipfs, jdk.management. java.net.http also serves HealthCheck.
RUN jlink \
      --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.sql,java.xml,jdk.httpserver,jdk.jfr,jdk.unsupported,jdk.naming.dns,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.management,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /jre

# distroless/cc: glibc + libgcc/libstdc++ for the QUIC natives, no shell or package manager.
FROM gcr.io/distroless/cc-debian12:nonroot
ENV JAVA_HOME=/opt/java PATH=/opt/java/bin:/usr/bin:/bin
COPY --from=build /jre /opt/java
WORKDIR /opt/sjws
# The jar's manifest Class-Path points at lib/ (Netty + QUIC natives) next to it.
COPY --from=build /out/ /opt/sjws/
COPY HTML/ /srv/www/
# Numeric uid/gid 10001 (no passwd entry needed); the base's nonroot user is 65532.
USER 10001:10001
# 8080/tcp HTTP/1.1 (and h2c); 8443 for TLS/HTTP/3 when --cert/--key are mounted and passed.
EXPOSE 8080/tcp 8443/tcp 8443/udp
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
  CMD ["java", "-Xshare:auto", "-XX:TieredStopAtLevel=1", "-Xmx16m", "-cp", "/opt/sjws/healthcheck", "HealthCheck", "http://127.0.0.1:8080/healthz"]
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/opt/sjws/sjws_nettask.jar"]
CMD ["8080", "/srv/www", "--healthz"]
