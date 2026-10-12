# sjws_nettask tests

JUnit 5 via Maven (`pom.xml` at repo root, reusing the NetBeans `src/` layout).
Tests live in `tests/` because `test/` is listed in `.gitignore`.

    mvn test                          # unit + in-process tests (JaCoCo agent attached)
    mvn verify                        # + package, the `jar` tests against the built jar, coverage gate
    mvn test -DexcludedGroups=h3      # skip everything that needs QUIC (Netty natives)

| Class | Covers |
|---|---|
| `HTTP/Http1ParserTest` | request-head parsing: smuggling vectors, limits, versions, absolute-form, Connection/Expect |
| `HTTP/HeaderTest`, `ToolsTest` | response head, dates, MIME, port parsing |
| `HTTP/FileServingTest` | file/listing/error responses without a network (containment, symlinks, dotfiles, 301/403/304, exact-length sends) |
| `ServerIntegrationTest`, `HardeningIntegrationTest` | real sockets: keep-alive, pipelining, deadlines, connection cap, leak check, process exit codes |
| `ServerConfigTest`, `BackoffTest` | config parsing/precedence, accept-loop back-off |
| `Http1GoldenTest`, `HTTP/Http1WriterTest` | byte-exact HTTP/1.1 responses; writer edge cases |
| `core/ContractTest`, `core/StaticFileHandlerTest` | protocol-neutral request/response records and handler |
| `core/PlatformEdgeCaseTest` | case/Unicode/`%2F` behaviour per file system |
| `core/SafeOpenTest` | security review M4: symlink/rename swaps of any path component between resolve and send, both open strategies (secure + fallback), and an HTTP/1.1 race test |
| `SjwsServerTest`, `AltSvcTest` | HTTP/1.1 + optional transport wiring (fake transport), Alt-Svc opt-in/withdrawal, shutdown order |
| `SourceGuardTest` | static rules: no `synchronized`, no Netty in core/HTTP-1, h3 doesn't import HTTP-1 |
| `tls/TlsMaterialTest` | PEM/encrypted keys, self-signed certificates, key-file/validity warnings |
| `h3/Http3ServerTest` | HTTP/3 server: parity with HTTP/1.1, ranges, back-pressure, write timeout, cancel, limits, idle/ALPN, Netty leak detector (`PARANOID`, checked in `@AfterAll`) |
| `h3/SecurityReviewTest` | the security review's repros, inverted: slot exhaustion, drip readers, reset floods, Retry tokens, receive windows, in-flight cap, GOAWAY drain, request validation, writer error/cancel paths |
| `h3/RateGuardTest` | minimum send-rate window |
| `h3/SjwsServerHttp3Test` | full server with real HTTP/3: Alt-Svc, encrypted keys, fallbacks (busy port, missing natives), `main` as a process |
| `h3/JarLaunchTest` (`@Tag("jar")`) | `java -jar target/sjws_nettask-*.jar --h3` gets HTTP/3 200; runs after `package` |
| `h3/QuicNativesReportTest` | prints whether the QUIC natives loaded (untagged, always runs) |

Tags: `h3` = needs QUIC; those classes also skip themselves (JUnit assumption) when
`Http3Server.isAvailable()` is false, and `QuicNativesReportTest` prints a `WARNING` line saying so.
`jar` = needs the packaged jar; excluded from the default Surefire run and run by the `jar-tests`
execution in the `integration-test` phase (without the JaCoCo agent: subprocesses aren't measured).

Tests named `BUG_*` were written against the original code and failed there.
New regressions can be added as `@Tag("bug")` tests first (`mvn test -DexcludedGroups=bug`
runs the rest), then untagged once fixed. The permission-based 403 tests are
skipped when run as root.
