# Installation and build

Basic multi-threaded (virtual threads) static file server: HTTP/1.0 and 1.1 over TCP, plus opt-in
HTTP/3 over QUIC, both served by the same protocol-neutral core. Requires Java 25+ (and Maven to build).

Quick start:

    mvn -B -DskipTests package
    java -jar target/sjws_nettask-0.2-SNAPSHOT.jar 8080 ./HTML
    curl -i http://127.0.0.1:8080/

Build and test:

    mvn verify      # tests + JaCoCo coverage gate (report: target/site/jacoco/index.html)

This builds `target/sjws_nettask-0.2-SNAPSHOT.jar` plus `target/lib/` (Netty and the QUIC natives,
named in the jar's `Class-Path`): copy both together. `mvn verify` also runs the jar itself with
`--h3` (tests tagged `jar`). The h3/tls coverage thresholds apply only on platforms that ship QUIC
natives (Maven profiles `h3-gate-*`, activated by OS/arch); elsewhere the HTTP/3 tests skip and the
build log says so. If the natives exist but can't load (e.g. a `noexec` `/tmp`), add
`-Dh3.gate.line=0 -Dh3.gate.branch=0 -Dtls.gate.line=0 -Dtls.gate.branch=0` (or `-DexcludedGroups=h3`).
