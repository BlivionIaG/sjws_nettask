# Docker

The repository's `Dockerfile` builds a two-stage image:

* **build**: `maven:3.9-eclipse-temurin-25`. `pom.xml` is copied and dependencies are fetched first
  (BuildKit cache mount on `~/.m2`), then `src/` is compiled and packaged with `-DskipTests`
  (tests run in CI, not in the image build).
* **runtime**: `gcr.io/distroless/cc-debian12` (glibc and libstdc++ for the QUIC natives, no shell)
  with a `jlink` runtime in `/opt/java` (modules from `jdeps` plus a fixed manual set; `tools/check-jlink-modules.sh` / docker.yml job `jlink-modules` fails on drift, see #411; debug info,
  man pages and headers stripped, zip-compressed), and `/opt/sjws/sjws_nettask.jar` and `/opt/sjws/lib/`
  (Netty and the Linux QUIC natives, on the manifest Class-Path; the macOS/Windows native jars are dropped). Runs as the non-root
  uid/gid 10001 (numeric, no passwd entry). The sample document root `HTML/` is at `/srv/www`.

Build and run:

    docker build -t sjws_nettask .
    docker run --rm -p 8080:8080 sjws_nettask
    curl http://localhost:8080/healthz      # ok

The default command is `8080 /srv/www --healthz`. Arguments after the image name replace it, so
pass the port and root first, as on the command line ([command-line.md](../user/command-line.md)):

    docker run --rm -p 8080:8080 -v "$PWD/site:/srv/www:ro" sjws_nettask 8080 /srv/www --healthz

`HEALTHCHECK` runs `docker/HealthCheck.java` (compiled in the build stage, `java.net.http`, exit 0 on 200; no curl in the image) against `http://127.0.0.1:8080/healthz` every 10 s. It needs `--healthz` (see
[observability.md](../operations/observability.md)) and port 8080; if you change either, override
the check with `docker run --health-cmd 'java -cp /opt/sjws/healthcheck HealthCheck http://127.0.0.1:PORT/healthz'` or `--no-healthcheck`. JVM heap is capped at 75 % of the
container memory limit.

Ports: `8080/tcp` is plain HTTP. `8443/tcp` and `8443/udp` are declared for TLS and HTTP/3; mount a
certificate and key and pass the matching TLS options to use them. Files in a mounted document
root must be readable by uid 10001.

CI: `.github/workflows/docker.yml` builds the image with Buildx (GitHub Actions cache), starts it,
waits for `healthy`, checks `/healthz`, a static file and that the process is not root, then removes
it. Nothing is pushed to a registry.


See [JVM flag profiles](../operations/jvm-flags.md) for G1 / ZGC / Serial recommendations measured outside the image.
