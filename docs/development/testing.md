# sjws_nettask tests

JUnit 6 (Jupiter) via Maven (`pom.xml` at repo root, reusing the NetBeans `src/` layout).
Tests live in `tests/` because `test/` is listed in `.gitignore`.

    mvn test                          # unit + in-process tests (JaCoCo agent attached)
    mvn verify                        # + package, the `jar` tests against the built jar, coverage gate
    mvn test -DexcludedGroups=h3      # skip everything that needs QUIC (Netty natives)

| Class | Covers |
|---|---|
| `http1/Http1ParserTest` | request-head parsing: smuggling vectors, limits, versions, absolute-form, Connection/Expect; every case runs through the stream API and the byte-level `HeadParser` (whole, chunked, every split, reused) with identical results |
| `http1/ChunkedDecoderTest` | chunked request bodies (RFC 9112 §7.1): sizes, extensions, trailers, bare CR/LF, overflow, line/trailer/body limits, every input split stops exactly at the body's end |
| `http1/HeaderTest`, `ToolsTest` | response head, dates, MIME, port parsing |
| `http1/FileServingTest` | file/listing/error responses without a network (containment, symlinks, dotfiles, 301/403/304, exact-length sends) |
| `ServerIntegrationTest`, `HardeningIntegrationTest` | real sockets: keep-alive, pipelining, deadlines, connection cap, leak check, process exit codes |
| `ServerConfigTest`, `BackoffTest` | config parsing/precedence, accept-loop back-off |
| `config/ConfigLoaderTest`, `config/RouteTableTest`, `ReloadServerTest`, `ReloadCoverageTest` | YAML file, routing table validation, immutable snapshot reload, challenge-port rebind, HTTP/3 cert swap on reload |
| `ReloadHookTest` | reload hook registry |
| `acme/AcmeTest`, `acme/AcmePebbleTest`, `tls/SwappableKeyManagerTest` | HTTP-01 handler, certificate storage, Pebble issuance (linux/amd64), hot cert swap |
| `admin/AdminTuiTest` | admin console: start/stop/reload of a real server, failed reload rollback, rendering, commands, log tail |
| `admin/cp/CpConsoleTest` | control-plane console: label selectors, diff, validation, stage/rollout/rollback on the fake CP, failover client, rendering, zero-cost guard |
| `observe/ObservabilityTest` | access-log formats, lock-free queue, non-blocking writer, metrics snapshot and Prometheus text, `/healthz` and `/metrics` |
| `observe/NodeReporterTest` | control heartbeat: 5 s cadence reuses the report, sequence advances only on a fresh take, cumulative 4xx/5xx, health and config generation suppliers, log2 histogram merge and p99 bucket bound |
| `observe/ClusterBoardTest` | cluster aggregate: per-node rate from cumulative snapshots, restart zeroes the interval, merged p99, errors by node and route, config drift, label-name cap |
| `observe/PrometheusScrapeTest` | `/metrics` text 0.0.4 (HELP/TYPE, histogram buckets, `_sum`, `_count`, escaped labels), cluster and gateway series only when attached, `promtool check metrics` and a live Prometheus scrape when those binaries are set (`PROMTOOL`, `PROMETHEUS`; required on Linux CI), Grafana JSON and PromQL under `contrib/grafana/` |
| `Http1GoldenTest`, `http1/Http1WriterTest` | byte-exact HTTP/1.1 responses; writer edge cases |
| `core/ContractTest`, `core/StaticFileHandlerTest` | protocol-neutral request/response records and handler |
| `core/HandlerMountTest`, `HandlerMountServerTest`, `h3/HandlerMountHttp3Test` | startup prefix mounts: none (same handler), one, several, overlap rejected before bind, router and global filters on HTTP/1.1, the same table on HTTP/3 |
| `core/FilterChainTest` | filter order, short-circuit, 500 on filter/handler failure, route patterns, `MiddlewareSpec` registration |
| `core/FilterDocumentTest` | declarative document: JSON round trip, scope, label selectors, rejected wire forms |
| `core/ChainControlTest` | atomic snapshot swap, stale and invalid documents keep the live chain, in-flight requests finish on the old chain |
| `ChainControlServerTest` | publishing a document onto a live server leaves the direct file path in place until the first apply |
| `gateway/ApiKeyFilterTest`, `gateway/JwtFilterTest`, `gateway/RateLimitFilterTest`, `gateway/CorsFilterTest`, `gateway/RequestSizeFilterTest`, `gateway/HeaderRewriteFilterTest`, `gateway/MiniJsonTest` | gateway filters: API key, JWT (HS/RS/ES, exp/nbf/iss/aud), striped GCRA rate limit, CORS preflight, size limits, header rewrite |
| `GatewayServerTest` | a declarative document of those filters on a live HTTP/1.1 server; a bad entry keeps the previous snapshot |
| `FilterIntegrationTest` | real HTTP/1.1 server: no chain by default, header/short-circuit filters, client address and raw headers, a replacement terminal handler, 500 then the next request on the same connection |
| `h3/FilterHttp3Test` | default HTTP/3 factory runs the same chain; a filter failure is a 500 on that stream |
| `core/PlatformEdgeCaseTest` | case/Unicode/`%2F` behaviour per file system |
| `core/SafeOpenTest` | security review M4: symlink/rename swaps of any path component between resolve and send, both open strategies (secure + fallback), and an HTTP/1.1 race test |
| `core/VerificationRegressionTest` | security verifications V3/V4/V6/V7: execute-only (711/0100) directories 403 by default and served with the opt-in, the attacker-made 311 directory race (0 served, 0 leaks), FIFO swapped in or present refused without blocking (thread + timeout guard), open failures after the decision answered 403/404, startup warnings |
| `core/FileCacheTest` | S3 small-file cache: hit/miss, HEAD/304 don't load, index under the directory key, spellings share one entry, query-string/404 flooding can't evict, LRU by entries and bytes, every hit validated (edit, same-size + restored mtime, rename swap, symlink/parent-dir swap to outside, chmod, delete), revalidation window with a fake clock, change during load not cached, load through `SafeOpen`, no I/O under the lock, concurrent accounting |
| `core/ETagTest` | strong/weak ETag rule (fake clock, sticky per version, bounded memo), If-None-Match matrix (lists, `*`, `W/`, malformed), INM over IMS precedence, 304 heads |
| `core/DateClockTest`, `http1/Http1WriterSingleWriteTest` | Date formatted once per second (fake clock); head + body in one `write` |
| `core/CachePerfReviewTest` | Fixes from the independent perf review: a cache hit answers 403 once a parent directory or the root became search-only (and not with `--allow-unlistable-dirs`); 64 concurrent misses of 256 KiB files under a 1 MiB budget keep 4 loads in flight and stream the rest; 32 concurrent misses of one file read it once (waiters fall back if that read is discarded); CLOCK second chance, bounded ring; cached and uncached weak/strong ETag agree after the policy forgets a young version |
| `core/StaticFileHandlerRangeTest` | S5 byte ranges: `Range` parser table (206 / ignored / 416), and per handler mode (uncached `FileBody` region, cache miss, cache hit slice): 206/416, multi and malformed ranges, If-Range strong ETag / exact date / weak / young versions, 304 precedence, HEAD ignores Range, listings/errors/405, index files, empty files |
| `DocsConsistencyTest` | S6: every `--flag` and `WEB_SERVER_*` variable of `--help` is in the docs option tables (`docs/user/command-line.md`, `http2.md`, `http3.md`, `docs/operations/observability.md`) and vice versa, documented defaults match the code, every source package has a JaCoCo gate |
| `Http1TransportTest` | S4 channel transport: slowloris 408 at the head deadline (silent and trickling), idle keep-alive close, stalled reader closed by the write deadline, slow-but-progressing 50 MB download survives, sendfile exact bytes (50 MB, uncached 200 KB), file shrink during sendfile closes without spinning (process CPU check), fragmented pipelining with a body, timer deregistration, reaper sweep per state (FIN sent at once on expiry), bounded transfer stalls; S5: 50 MB download resumed from an offset with Range + If-Range byte-exact (sendfile), shrink during a ranged sendfile, uncached ranges on both write paths; perf review N1/N2: an unfinished body is cut at the head deadline with a 408, with or without `100 Continue` |
| `core/SecurityHeadersTest` | `X-Content-Type-Options: nosniff` on every file and generated page; script-free, frame-free `Content-Security-Policy` on listings, errors and redirects |
| `core/RenderingTest` | reason phrases for every registered status (empty for unregistered codes), error page titles, HEAD error heads, MIME/charset of the added static-file types |
| `core/AccessRulesTest` | access rules: glob table (anchored/unanchored, `*`/`?`/`**`, "everything below"), deny list > rules > dotfile default, allow wildcards never expose dotfiles but literals can (`.well-known/acme-challenge`), linear matching with many `**` on deep paths, malformed rules, handler wiring with and without the cache (403/404, normalized and decoded paths, listings filtered, denied index falls back to listing), symlinks to denied/dot paths refused, owner/world-readable policy (re-checked on cache hits, fails closed without POSIX attributes), custom `AccessPolicy` seam. `AccessRulesBench` (not a test) prints the per-request cost |
| `config/RouteErrorsTest` | per-route `errors: problem` (RFC 9457) and `listing:`: YAML parsing and rejections, no wrapper when off, static 404/403/405 and HEAD as problem+json with the client's path and kept fields, proxy 502/504 converted while an upstream's own 503 body is relayed, 401/403/429/502/503/504 from any inner handler, JSON escaping, `listing: false` 404 sharing the cache |
| `core/PerIpLimitTest` | per-address connection counter, IPv6 counted per /64 |
| `SjwsServerTest`, `AltSvcTest` | HTTP/1.1 + optional transport wiring (fake transport), Alt-Svc opt-in/withdrawal, shutdown order, `--backlog` reaches listen(2) (checked with `ss`) |
| `ha/SnapshotHolderTest`, `ha/HealthBoardTest`, `ha/HaLifecycleTest` | atomic snapshot publish, precomputed `/healthz` and `/readyz`, drain (delay, in-flight, `Connection: close`), accept pause |
| `cluster/QuorumPolicyTest`, `cluster/HeartbeatCodecTest`, `cluster/MembershipTest`, `cluster/HeartbeatServiceTest`, `cluster/HeartbeatAuthTest` | majority and witness quorums, 64-byte heartbeat codec, HMAC-SHA256 datagrams (spoof, replay, stale, rotation, wrong key, sender restart, receiver restart, cross-peer replay, non-member), failure detector (suspect, dead, incarnation refute, indirect probe), simulated loss and UDP heartbeats |
| `cluster/RaftClusterTest`, `ha/GenerationSyncTest` | per-group Raft (election, batch commit, strict vs relaxed fsync, witness, minority, joint membership, transfer, snapshot compact, preferred campaign, failback), HMAC generation publish and key mode 0600 |
| `ha/FailoverTest` | VIP claim and release-before-expiry, active/active claims nothing, `/readyz` rolling restart, `SO_REUSEPORT` hand-over and the unavailable path |
| `ha/ClusterBenchTest` | cluster-mode bench process runs a second node and the heartbeat thread |
| `ha/HeartbeatAuthClassLoadTest` | HA off does not load `HeartbeatAuthenticator` or `ClusterKey`; cluster mode without a key exits 64 |
| `SingleNodeHotPathTest` | clustering off: file handler, no role supplier, no drain supplier, no heartbeat or raft thread |
| `clusterharness/ClusterScenariosTest`, `CpVoterRestartTest`, `ConnectedDataNodeTest` (`@Tag("cluster")`), `NodeFixturesTest` | N control + M data nodes on the file-serving harness (ephemeral port, temp root, HTTP/1.1 client). Gateway-off and standalone serve the same file with no `cp-` thread. 3 or 5 real `CpStore` voters on one `SimTransport` and a manual clock: election, network partition, leader kill, slow node and config rollback, with the content checked on every data node during the operation; a killed voter restarts from its log. `NodeFixtures.pending` starts a node at `connection=pending`, `NodeFixtures.connected` a data node joined through the real watch. Nothing is `@Disabled` |
| `clusterharness/MsgReplClusterTest` (`@Tag("cluster")`) | HA messaging across real processes (`MsgReplCluster`): 2 data nodes and a witness, each a `Sjws_nettask` JVM from its own `ha.messaging` config, real TCP Raft links through per-direction `TcpLinkProxy`s, file Raft logs and the on-disk ledger. Leader `kill -9` with failover, restart and catch-up, witness loss, a partition between the data nodes, and the `on` to `off` rollback on one node. A producer with unique record ids and read-back deduplication (#254) checks that every acked record is stored exactly once, in the same order on every data node, at the offset its ack gave, in ack order, with offsets contiguous from 0. The leader kill holds one produce open on the dying leader so every run has an unknown outcome to resolve; turning replication back `on` after a local write in `off` must exit 64. Excluded from PIT (`cluster`). Gate G3 of the HA messaging default-on proposal |
| `SourceGuardTest` | static rules: no `synchronized`, `computeIfAbsent` only without I/O, no Netty in `core`, `http1` or `cluster`, `core` imports no transport or sockets, `h3` doesn't import HTTP/1, `cluster` doesn't import `ha` or HTTP/1, nothing outside `swarm` names it (except the `OptionalModules` class-name string), `swarm.wire` imports only `core`, the control-role `cp` types stay inside `cp` |
| `tls/TlsMaterialTest` | PEM/encrypted keys, self-signed certificates, key-file/validity warnings |
| `h2/Http2ServerTest` | HTTP/2: ALPN h2 + http/1.1 fallback, parity with HTTP/1.1, concurrent streams, h2c Upgrade and prior knowledge, stream cap, rapid reset (GOAWAY ENHANCE_YOUR_CALM), CONTINUATION flood, header list size, flow control on a large file, stalled stream, in-flight cap |
| `h2/Http2JuryTest` | security jury regressions: PINGs / unfinished header block don't keep an idle h2 connection, open streams do, never-ending request reset with NO_ERROR, slowloris on the HTTP/1.x fallback, header timeout vs keep-alive and upgrade, PING flood without reading |
| `h2/Http2BudgetTest` | review fixes: zero-window clients don't delay another client (<1 s), per-connection file/in-flight budgets (REFUSED_STREAM), zero-window CANCEL, per-IP cap, HTTP/1 fallback in-flight slot (503), GOAWAY on stopAccepting, drain on close |
| `h2/Http2EdgeTest` | HTTP/2 listener edges: HTTP/1.x fallback keep-alive/pipelining/close, refusals, 500s, connection caps, idle close, stopAccepting/close, busy port, header mapping |
| `h2/H2ConfigAndWiringTest` | `--h2`, `--h2-port`, `--h2c-port` parsing/warnings and `SjwsServer` wiring (fallback when HTTP/2 fails, observe filter at startup and after `metricsSnapshot()`) |
| `h2/ResponseWriterTest` | HTTP/2 response writer outcomes: swapped/shrunk files, stall, rejected, closed, sink failures |
| `h3/Http3ServerTest` | HTTP/3 server: parity with HTTP/1.1, ranges, back-pressure, write timeout, cancel, limits, idle/ALPN, Netty leak detector (`PARANOID`, checked in `@AfterAll`) |
| `h3/SecurityReviewTest` | the security review's repros, inverted: slot exhaustion, drip readers, reset floods, Retry tokens, receive windows, in-flight cap, GOAWAY drain, request validation, writer error/cancel paths |
| `h3/VerificationTest` | security verifications V1/V2/V3/V4/V5/V7 over HTTP/3: one-way (spoofed) handshakes hold no connection slot, handshake timeout and half-open cap, per-address cap, fair in-flight share, slow streams cut only under contention, 711 directories (403 / opt-in 200), the 311 race, FIFOs |
| `h3/RateGuardTest` | per-connection minimum send-rate window |
| `h3/SjwsServerHttp3Test` | full server with real HTTP/3: Alt-Svc, encrypted keys, fallbacks (busy port, missing natives), `main` as a process |
| `h3/JarLaunchTest` (`@Tag("jar")`) | `java -jar target/sjws_nettask-*.jar --h3` gets HTTP/3 200; runs after `package` |
| `h3/QuicNativesReportTest` | prints whether the QUIC natives loaded (untagged, always runs) |
| `MainTest` | entry point: `--help`/`-h` (exit 0), usage errors (64), bad root (2), busy port (1), warnings, startup/shutdown messages |
| `DaemonTest` | `--daemon`/`--stop`/`--status`/`--restart`, pid-file lock and stale/reused pids, log file, graceful drain on close |
| `ServiceDaemonTest`, `RoleSampleTest`, `service/BootstrapTest` | control/data bootstrap and join token (mode, symlink, no secret on the command line or in status), `--status` role/connection/config-version, HA drain on the shutdown hook, systemd unit checks, role precedence (`--role`, then `WEB_SERVER_ROLE`, then `node.mode`), SIGHUP reload, drain budget, contrib role samples against schema 1, standalone does not load `sjws_nettask.service` |
| `ClientManagerFaultTest`, `Http1EdgeTest` | accept-loop faults (back-off, reset before registration, setOption failure), EOF/CR/body-drain edge cases, failure log |
| `http1/LegacyApiTest`, `core/CoreApiTest` | legacy wrappers (HTML/Page/Header/CONSTANTS) and small core APIs (ResponseHeaders, Mime, Response, FileCache bounds) |

The tests under `routing/`, `cp/`, `messaging/`, `swarm/` and `controlplane/` are described with their design docs: [proxy routing](../design/proxy-routing.md), [control plane](../design/control-plane.md), [messaging](../messaging/design.md) and [agent swarm](../design/agent-swarm.md).

Tags: `h3` = needs QUIC; those classes also skip themselves (JUnit assumption) when
`Http3Server.isAvailable()` is false, and `QuicNativesReportTest` prints a `WARNING` line saying so.
`jar` = needs the packaged jar; excluded from the default Surefire run and run by the `jar-tests`
execution in the `integration-test` phase (without the JaCoCo agent: subprocesses aren't measured).
`cluster` = the multi-node harness (`ClusterScenariosTest`, `ConnectedDataNodeTest`, `CpVoterRestartTest`, `MsgReplClusterTest`).
Every case is live and runs in CI (`mvn verify`, the `-Dgroups=cluster` step, and nightly in `cluster-tests.yml`).
`e2e` = `FileServingEndToEndTest`, also run alone by its own CI step.

Tests named `BUG_*` were written against the original code and failed there.
New regressions can be added as `@Tag("bug")` tests first (`mvn test -DexcludedGroups=bug`
runs the rest), then untagged once fixed. The permission-based 403 tests are
skipped when run as root.

## Behaviour audit

Every user-visible behaviour and the test that asserts it.

| Behaviour | Asserted by |
|---|---|
| every `--flag` and `WEB_SERVER_*` variable (value, default, invalid value, env over argument) | `ServerConfigTest`, `DaemonTest`; `DocsConsistencyTest` keeps `--help` and the docs option tables in sync |
| `--daemon`, `--stop`, `--status`, `--restart`, pid file, log file, graceful shutdown, `--role`, `--bootstrap`, join token, control/data status, `node.mode` precedence, contrib role samples | `DaemonTest`, `ServiceDaemonTest`, `RoleSampleTest`, `service/BootstrapTest` |
| access log (common, combined, JSON), `/healthz` opt-in (a document-root file is served when it is off), `/metrics` off / localhost / on, counters stay off until armed; the observe filter is order 0 and is absent while off; attaching cluster or gateway series does not install that filter | `observe/ObservabilityTest`, `observe/PrometheusScrapeTest`; HTTP/3 recording in `h3/ObservabilityHttp3Test`; HTTP/2 recording in `h2/H2ConfigAndWiringTest` |
| control heartbeat report (cumulative snapshot, 5 s cache, histogram merge by index) | `observe/NodeReporterTest` |
| cluster requests/s, merged p99, errors by node and route, config drift, shown on the admin status view when a board is attached | `observe/ClusterBoardTest`, `admin/AdminTuiTest` |
| gateway mode off matches standalone file serving (ephemeral ports, no `cp-` thread, applied version 0) | `clusterharness/ClusterScenariosTest` |
| `--help` / `-h` text, exit 0 | `MainTest` |
| exit codes 0 / 1 (cannot listen) / 2 (bad root) / 64 (usage) | `MainTest` |
| `WARN: Unknown option`, `--allow-unlistable-dirs`, cache > heap/2, key-file permissions, cert validity | `MainTest`, `SjwsServerTest`, `tls/TlsMaterialTest` |
| `Server started: ...`, `HTTP/3 unavailable` fallback, `Server Terminated properly !` | `MainTest`, `SjwsServerTest`, `h3/SjwsServerHttp3Test` |
| statuses 200 206 304 301 400 403 404 405 408 413 414 416 431 500 501 505 | `Http1GoldenTest`, `http1/Http1ParserTest`, `http1/FileServingTest`, `core/StaticFileHandlerRangeTest`, `Http1TransportTest`, `h3/Http3ServerTest`, `core/FilterChainTest`, `FilterIntegrationTest` |
| headers Date, Server, Content-Type/charset, Content-Length, Last-Modified, ETag, Accept-Ranges, Content-Range, Location, Connection, Alt-Svc, Allow, X-Content-Type-Options, Content-Security-Policy | `Http1GoldenTest`, `core/ETagTest`, `AltSvcTest`, `core/SecurityHeadersTest`, `h3/Http3ServerTest` |
| HTTP/1.1 keep-alive, pipelining, timeouts, limits | `ServerIntegrationTest`, `HardeningIntegrationTest`, `Http1TransportTest` |
| HTTP/3 parity, limits, Alt-Svc, launch from the jar | `h3/*` |

## Not covered on purpose

Lines JaCoCo reports as missed that no behaviour test can reach (or only by faking the JDK):

- `Sjws_nettask.main`: runs only in the child JVM of `JarLaunchTest` (all its logic is in the tested `run`).
- `Sjws_nettask`: the hook-removal `IllegalStateException` (JVM already shutting down) and the start `IOException` when the root vanishes between check and bind.
- `ConnectionReaper`: the sweep's `RuntimeException` guard, a spurious interrupt, `close()` failing on a socket.
- `Http1Connection`: non-routine failure while interrupted; `getRemoteAddress` failing on a closed socket.
- `ClientManager` / `Transport`: the default no-op `stopAccepting`.
- `PerIpLimit`, `SelfSignedDer`: `UnknownHostException` on a byte-array address (impossible for 4/16 bytes).
- `TlsMaterial`: `GeneralSecurityException`/`IOException` wrappers around JDK providers that always exist (EC, PKCS#12 in memory).
- `Http3Writer`: `catch (Error)`; `Http3Server`/`Http3RequestHandler`: interrupts and `RejectedExecutionException` during shutdown, connection-closed races.
- `FileCache`: the non-UNIX stat path (Linux CI), `ExecutionException` (the leader never completes exceptionally), the CLOCK `e == null` branch, concurrent-load races (`old != null`).
- `SafeOpen`: non-`SecureDirectoryStream` and non-`FileChannel` providers, I/O errors after a successful open.
- `StaticFileHandler`: the `UncheckedIOException` unwrap from a directory stream failing mid-iteration.

## Known platform gaps (macOS)

The suite is green on Linux. On macOS (CI run 38212694685, manual job only) three tests failed and need a fix in the code, not the test:

- `core/SafeOpenTest.parentSymlinkRaceNeverLeaksOverHttp1`: a file was served while a parent directory was swapped for a symlink (the race guard relies on Linux `SecureDirectoryStream`/`O_NOFOLLOW` behaviour).
- ~~`core/FileCacheTest.searchOnlyDirectoryStays403`~~ (fixed): without `SecureDirectoryStream` every open took the path fallback, which skipped the search-only directory rule; the fallback now checks every directory from the root down (`SafeOpenTest.fallbackRefusesFilesBelowSearchOnlyDirectories`).
- `HardeningIntegrationTest.trickledRequestIsCutOffAtDeadline`: the client sees a reset instead of the 408 (macOS sends RST when a socket with unread data is closed).

## Mutation testing

`mvn -P pitest test-compile org.pitest:pitest-maven:mutationCoverage` (about 70 min on 4 threads; CI runs it weekly and on manual dispatch).
On 2026-10-12: 1524 mutants, 87% killed (test strength 89%). Most survivors are log calls, `flush()` on paths that also flush later, password scrubbing (`clearPassword`, `Arrays.fill`), and boundary flips on limits that tests check with margins.
