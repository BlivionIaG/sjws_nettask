# Integration and end-to-end tests

How `sjws_nettask` is tested, from the suite in `tests/` and the workflow in
`.github/workflows/ci.yml`. The catalog of individual classes is `docs/development/testing.md`.
This file states the layering, the file-serving harness, and what the server does
not implement yet.

## Scope

In scope:

- JUnit 6 tests under `tests/` (Maven `testSourceDirectory`; `test/` is gitignored).
  Surefire's working directory is the repository root.
- The three layers below: unit tests with no listener, in-process tests that open
  real sockets, and process or full-listener checks.
- Protocols the tree actually speaks: HTTP/1.1 on TCP, and HTTP/3 (QUIC) through
  Netty when the quiche natives load.
- The harness `tests/sjws_nettask/e2e/FileServingEndToEndTest.java`: a real
  `SjwsServer` on port 0, a temporary document root, and `java.net.http.HttpClient`.

Out of scope for the harness, because the server has no such listener:

- HTTP/2.
- TLS on the TCP port (HTTPS). TLS that does exist is QUIC for HTTP/3 only.

`mvn test` runs every test except those tagged `jar`. `mvn verify` also packages
the jar, runs the `jar` tests, and enforces the JaCoCo package gates in `pom.xml`.
Subprocess tests are not in the coverage data.

## Layers

### Unit

No accept loop. Parsers, records, and the static-file handler are called directly.
Temporary directories (`@TempDir`) are normal here; they are fixtures for the
handler, not a listening server. Examples: `http1/Http1ParserTest`,
`http1/ChunkedDecoderTest`, `http1/Http1WriterTest`, `http1/FileServingTest`,
`core/StaticFileHandlerTest`, `core/StaticFileHandlerRangeTest`, `core/FileCacheTest`,
`core/ETagTest`, `core/RenderingTest`, `ServerConfigTest`, `tls/TlsMaterialTest`,
`DocsConsistencyTest`, `SourceGuardTest`.

`core/SafeOpenTest` is the same kind of test plus one case that writes an HTTP/1.1
response through `Http1Writer` on a stream. It does not bind a port.

### Integration

A real listener in the same JVM, and a client on a socket or a UDP/QUIC channel.

HTTP/1.1 today is usually `ClientManager` on a `ServerSocketChannel` bound to
loopback port 0 (`ServerIntegrationTest`, `HardeningIntegrationTest`,
`Http1TransportTest`, `Http1GoldenTest`). `Http1EdgeTest` and `SjwsServerTest`
start `SjwsServer` itself. Clients are mostly raw `Socket`s via `HttpTestSupport`.
`ServerIntegrationTest` also has one `java.net.http.HttpClient` request.
`ServerIntegrationTest` serves the repository `HTML/` tree (Surefire's working
directory is the repo root). `HardeningIntegrationTest`, `Http1TransportTest`,
and `Http1EdgeTest` serve a `@TempDir`.

HTTP/3 tests (`h3/Http3ServerTest`, `h3/SecurityReviewTest`, `h3/VerificationTest`,
`h3/RateGuardTest`, `h3/SjwsServerHttp3Test`) start the real Netty `Http3Server`
on an ephemeral UDP port with a temporary root and `h3/H3TestClient`. They are
tagged `h3` and skip themselves when `Http3Server.isAvailable()` is false.
`SjwsServerTest` stubs that transport; it does not speak QUIC.

### End to end

A full server path, checked with a real client.

- `FileServingEndToEndTest` (tag `e2e`) starts `SjwsServer.start` with
  `ServerConfig.builder(tempRoot).port(0)`. That is the same listener `main`
  starts: TCP accept loop, `StaticFileHandler`, HTTP/1.1 writer. The port is
  whatever the kernel assigned (`tcpPort()`). The document root is a `@TempDir`
  with one file, `hello.txt`. The client is `java.net.http.HttpClient` forced to
  HTTP/1.1, with no proxy. It asserts:
  - `GET /hello.txt` → 200, body equal to the file, `Content-Type:
    text/plain; charset=utf-8` (from `core.Mime` for `.txt`).
  - `GET /no-such-file.txt` → 404.
- Process checks already in the suite, separate from that harness:
  `HardeningIntegrationTest` launches `sjws_nettask.Sjws_nettask` for exit codes
  0, 1, 2, and 64; `ServerIntegrationTest` launches it with `WEB_SERVER_DIR`
  pointed at a `@TempDir`; `h3/JarLaunchTest` (tags `jar` and `h3`) runs
  `java -jar` after `package` and fetches HTTP/3. Those process tests pass a
  concrete TCP port. The command-line and `WEB_SERVER_PORT` parsers only accept
  ports from 1 to 65535, so they cannot request port 0. Several of them take a
  port from a short-lived `ServerSocket(0)` and then bind it again, which can
  race. The harness does not do that: `SjwsServer.bind` accepts 0 and the test
  reads the bound port after `start` returns.

## Ephemeral port

`ServerConfig` documents TCP port 0 as ephemeral. `SjwsServer.bind` opens a
`ServerSocketChannel`, sets `SO_REUSEADDR`, and binds `new InetSocketAddress(port)`
(the wildcard address) with the configured backlog. `tcpPort()` reads the address
after the bind. `start` returns only after that bind has succeeded and the accept
thread has been started. HTTP/3 uses its own UDP port (`h3Port` 0, or the same
number as the TCP port when `h3Port` is -1). The harness sets `h3Enabled(false)`,
so it binds TCP only.

Integration tests that construct `ClientManager` themselves bind loopback port 0
and read `getLocalPort()` the same way. They do not go through `SjwsServer.bind`,
so they do not listen on the wildcard address.

## Temporary document root

`@TempDir` is the fixture. The harness writes `hello.txt` in UTF-8 before
`start`. The server is given that directory as `ServerConfig.root`. Nothing is
read from `./HTML`. JUnit deletes the directory after the test. Process tests
that need a root either pass the temp path as the second argument or set
`WEB_SERVER_DIR`, and they remove `WEB_SERVER_DIR` / `WEB_SERVER_PORT` from the
child environment when the test wants the arguments to win (`HardeningIntegrationTest`).

## Protocol coverage

| Protocol | In the server | In the tests |
|---|---|---|
| HTTP/1.1 | TCP listener. Cleartext. This is what `curl http://` hits. | Unit parser and writer tests; socket integration tests; `FileServingEndToEndTest` via `HttpClient` with `Version.HTTP_1_1`. |
| HTTP/2 | Not implemented. A request line `HTTP/2.0` is rejected by `Http1Parser` with status 505. There is no h2c upgrade and no HTTP/2 codec. | Only the 505 cases (`Http1ParserTest`, `Http1GoldenTest`, `HardeningIntegrationTest`). |
| HTTP/3 | Opt-in (`--h3`). Netty `netty-codec-http3` and the bundled quiche natives (Linux x86_64/aarch64, macOS x86_64/aarch64, Windows x86_64). TLS 1.3 is the QUIC handshake (`QuicSslContextBuilder`), with a configured PEM chain or a generated self-signed certificate. | `h3/*` when the natives load; otherwise the tests assume false and skip. JaCoCo gates for `sjws_nettask.h3` and `sjws_nettask.tls` are 0 off those platforms. |
| TLS on TCP | Not implemented. `TlsMaterial.keyManagerFactory()` builds a JSSE `KeyManagerFactory` for a future HTTPS listener. No TCP socket uses it. The HTTP/3 docs state that browsers need HTTPS before they will follow `Alt-Svc`, and that this server does not provide that listener. | `tls/TlsMaterialTest` loads PEM material and checks the JDK still advertises TLS 1.3. No HTTPS client connects to the TCP port. |

## Timeouts and flakiness

The harness does not sleep and does not retry. `start` has already bound the
port. Each request has a connect timeout of 2 seconds and a request timeout of
4 seconds. The class carries `@Timeout(10)`. A missed 200 or 404 fails the test.

The rest of the suite:

- Socket reads use `setSoTimeout` (often 2–15 seconds). A hang becomes
  `SocketTimeoutException` instead of waiting for the CI job timeout.
- Readiness is a bounded poll or a blocking read. `HardeningIntegrationTest.waitUntil`
  and `Http1TransportTest.waitFor` check a condition until a deadline (5 seconds,
  or the caller's limit) and sleep 10 milliseconds between checks. Process tests
  block in `readLine` until a `Server started:` line or the child exits
  (`waitForLine`), or they use `Process.waitFor` with a 5–10 second limit.
- Some tests call `Thread.sleep` as the stimulus: they must stay idle longer than
  a configured header, idle, or write deadline, or longer than an HTTP/3 rate
  window. Those sleeps are not a stand-in for "wait until the server is up".
- There is no JUnit retry extension and no Surefire rerun. A failed assertion is
  a failed test. HTTP/3 does not retry a missing native library; it skips.
- Known macOS failures are listed in `docs/development/testing.md`. `.github/workflows/ci.yml`
  runs macOS only on `workflow_dispatch`. Linux is the required platform.

## CI layout

`.github/workflows/ci.yml`:

- Triggers: push to `master`, every pull request, `workflow_dispatch`, and a
  weekly cron (`0 3 * * 1`) for mutation only.
- `permissions: contents: read`. Concurrency group `ci-<workflow>-<ref>`,
  cancel in progress on pull requests.
- Job `verify` (not on the cron): Temurin 25, `ubuntu-latest`. `macos-latest` is
  added only for `workflow_dispatch`. Timeout 45 minutes. Steps: checkout,
  `actions/setup-java@v4` with the Maven cache, `mvn -B verify`, then the
  end-to-end step below, then upload `target/site/jacoco/`.
- `mvn -B verify` is the unit and integration suite, the `jar` tests (Surefire
  execution `jar-tests` on the `integration-test` phase, without the JaCoCo
  agent), and the coverage gate. `FileServingEndToEndTest` is part of that
  default Surefire run (it is not in the `jar` exclude).
- The following step runs the harness on its own and fails if the `e2e` group
  matches nothing:

  `mvn -B -Dgroups=e2e -Dsurefire.failIfNoSpecifiedTests=true test`

- The cluster harness (tag `cluster`) runs the same way. Live cases start several
  data nodes. The fault scenarios are `@Disabled` and do not fail this step:

  `mvn -B -Dgroups=cluster -Dsurefire.failIfNoSpecifiedTests=true test`

- `tools/gateway-bench.sh` then runs a short wrk comparison (warm-up 1 s, measure 3 s)
  of standalone vs gateway-off on Linux, after `apt-get install wrk`. It fails if either mode
  reports request errors or zero throughput. The 30 s comparison is local; see
  `bench/README.md`. macOS CI (manual dispatch only) skips that step.

- Job `mutation` (cron or `workflow_dispatch` only): `ubuntu-latest`, timeout
  120 minutes, PIT via `mvn -B -P pitest test-compile org.pitest:pitest-maven:mutationCoverage`.
  Not part of a pull request.

`.github/workflows/release.yml` is a tag workflow. It is not the test matrix.

Local equivalents, with Java 25:

```
mvn test
mvn verify
mvn -B -Dgroups=e2e -Dsurefire.failIfNoSpecifiedTests=true test
mvn -B -Dgroups=cluster -Dsurefire.failIfNoSpecifiedTests=true test
mvn test -DexcludedGroups=h3
```

## Cluster harness

`tests/sjws_nettask/clusterharness/` starts M data nodes the same way
`FileServingEndToEndTest` does: `SjwsServer.start` with `port(0)`, a `@TempDir`
document root, and `java.net.http.HttpClient` forced to HTTP/1.1 with no proxy.
`start` returns only after the accept loop is bound, so the live tests do not sleep.

Control nodes go through `ControlPlane`. The design is
[PR #38](https://github.com/BlivionIaG/sjws_nettask/pull/38)
(`docs/design/control-plane.md`). `sjws_nettask.cp.CpStore` is a single voter and
`AdminServer` can bind an ephemeral port. Neither implements `ControlPlane`
(3 or 5 voters, partition, leader kill, `ClusterClock` delay, or a push that data
nodes apply). `PendingControlPlane` throws `ControlPlanePendingException` on every
operation. It does not elect a leader or apply a snapshot. `ClusterHarness.open`
with 3 or 5 control nodes, or with data role `DATA`, throws that exception before it binds.

`NodeFixtures.pending(dir, "data")` writes the bootstrap files under that temp directory
(`state` is `dir/state`, not the working directory). A process started from that file
reports `connection=pending`. That replaces the copy
[PR #95](https://github.com/BlivionIaG/sjws_nettask/pull/95) inlined in
`RoleClassLoadTest`. `NodeFixtures.connected(dir)` is the data-role variant against a
live control plane. It throws, and `ConnectedDataNodeTest` is `@Disabled`, until a
data-node watch client reports `connection=connected` and applies a pushed version.
Attaching a `ControlPlaneBinding` in the test is not that client.

Live (tag `cluster`, run by `mvn verify` and by the CI group step):

- Three gateway-off data nodes on distinct ephemeral ports serve `hello.txt`
  (200, body, `text/plain`) and a missing path (404). Applied version is 0.
  No thread name starts with `cp-`.
- Gateway-off and standalone return the same status, body, and content headers
  for that file. Neither attaches `HaSettings`.
- A control-node count other than 0, 3, or 5 is rejected.
- `PendingControlPlane.available()` is false and the exception text links PR #38.

Pending (`@Disabled`, the message starts with `TODO` and the PR URL):

| Scenario | What it will assert once `ControlPlane.available()` is true |
|---|---|
| Network partition | Push converges with no content errors. Isolating data nodes from every voter leaves them on that version and hash, still serving the file. After heal, a later push converges. |
| Control-plane leader kill | In-process kill, then one election-timeout advance on `ClusterClock.Manual`. A different voter is `leaderReady` on its own ephemeral admin port. A following push converges, and content stays 200. |
| Slow node | A one-way watch delay on one data node. Content stays 200 during the push. Advancing the manual clock releases the delay and every node, including the slow one, reports the new version. |
| Config rollback | Rollback returns a higher version whose content hash is the earlier version. Every data node converges on that version and hash. Content stays 200 during the rollback. |

Enable a scenario by removing `@Disabled` only when `ControlPlane.available()` is true.
Point `ClusterHarness.newControlPlane()` at the real factory. Leaving the stub in
place makes the scenario fail, which is the point.

## Roadmap

Not implemented. Nothing above depends on these.

- HTTP/2 listener. Until one exists, the only HTTP/2 test is the 505 rejection
  of `HTTP/2.0` on the HTTP/1.1 parser. Do not point `HttpClient`'s HTTP/2
  version at this port.
- HTTPS on the TCP port. QUIC TLS and `TlsMaterial` stay as they are. A TCP TLS
  listener would be a new server path; there is no client harness for it.
- Accepting port 0 on the command line and in `WEB_SERVER_PORT`, so a subprocess
  can bind an ephemeral TCP port and print it (the startup line already includes
  `HTTP/1.1 on tcp/<port>`). Today those parsers replace 0 with the default and
  warn.
- HTTP/3 inside `FileServingEndToEndTest`. The same temporary root and ephemeral
  port are already covered by `h3/Http3ServerTest` and `h3/JarLaunchTest`.
- Dropping fixed `Thread.sleep` calls that only poll for a condition, in favour
  of the deadline loops the transport tests already use. Sleeps that exist to
  outlast a deadline stay.
