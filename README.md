# sjws_nettask
Simple Java Web Server

Basic multi-threaded (virtual threads) static file server: HTTP/1.0 and 1.1 over TCP, plus opt-in
HTTP/3 over QUIC, both served by the same protocol-neutral core. Requires Java 25+ (and Maven to build).

**Vision (roadmap, not shipped):** the end goal is a reliable, fast, low-latency message-oriented
middleware that is also a web server. Messaging is being designed now; today the server serves
static files only.

Documentation: [configuration reference](docs/configuration.md) ·
[architecture](ARCHITECTURE.md) · [tests](tests/README.md) · [contributing](CONTRIBUTING.md) ·
[releasing](RELEASING.md). The option tables below are the canonical list of options
(`DocsConsistencyTest` keeps them in sync with `--help`).

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

Run:

    java -jar target/sjws_nettask-0.2-SNAPSHOT.jar [port] [html folder] [options]

| Option | Environment | Default | |
|---|---|---|---|
| `[port]` | `WEB_SERVER_PORT` | 8080 | TCP port |
| `[html folder]` | `WEB_SERVER_DIR` | `./HTML` | document root (must exist) |
| `--max-connections=N` | `WEB_SERVER_MAX_CONNECTIONS` | 2048 | concurrent connections; extra ones wait in the backlog |
| `--backlog=N` | `WEB_SERVER_BACKLOG` | 1024 | TCP accept backlog (connections queued by the kernel before they are accepted); the OS caps it (Linux: `net.core.somaxconn`, 4096 by default since 5.4) |
| `--idle-timeout=S` | `WEB_SERVER_IDLE_TIMEOUT` | 30 | keep-alive idle timeout (seconds) |
| `--header-timeout=S` | `WEB_SERVER_HEADER_TIMEOUT` | 10 | time to receive a complete request head (seconds) |
| `--max-connections-per-ip=N` | `WEB_SERVER_MAX_CONNECTIONS_PER_IP` | 256 | HTTP/1.1 connections from one client address (IPv6: one /64); extra ones are closed at once without a response |
| `--allow-unlistable-dirs` | `WEB_SERVER_ALLOW_UNLISTABLE_DIRS` | off | serve files below directories the server can't read (mode `--x`) through a weaker path-based open; see Platform notes |
| `--cache-bytes=N[k\|m\|g]` | `WEB_SERVER_CACHE_BYTES` | 64m | small-file cache size (see below); `0` disables it |
| `--write-timeout=S` | `WEB_SERVER_WRITE_TIMEOUT` | 30 | a response that makes no progress for this long is closed (HTTP/1.1) or reset (HTTP/3); for HTTP/3 also the window of the minimum send rate |
| `--config=PATH` | `WEB_SERVER_CONFIG` | | YAML configuration file (see [docs/config.md](docs/config.md)); the environment wins |
| `-h`, `--help` | | | print usage and exit |
| `--daemon` | | | detach and run in the background; the parent exits once the child is listening |
| `--pid-file=PATH` | `WEB_SERVER_PID_FILE` | sjws_nettask.pid | process-id file; written by `--daemon`, and whenever this option is set |
| `--log-file=PATH` | `WEB_SERVER_LOG_FILE` | sjws_nettask.log | log file for `--daemon`, and for a foreground process when this option is set |
| `--shutdown-timeout=S` | `WEB_SERVER_SHUTDOWN_TIMEOUT` | 10 | seconds to finish in-flight responses after SIGTERM/SIGINT (`0` closes them at once) |
| `--stop` | | | stop the server recorded in the pid file (SIGTERM, then wait) |
| `--status` | | | print whether that server is running |
| `--restart` | | | `--stop`, then start in the background |

### Observability

Off by default, and off means the request path does not touch it. The transports are given
`RequestObserver.NONE`. A request then does one volatile read of that slot and a reference compare,
and stops: no `System.nanoTime`, no `LongAdder` update, no latency compare-and-set, and no inspection
of the path for `/healthz` or `/metrics`. A file at either path is served from the document root, the
same as a build without this feature.

Counters, the access log and the probes turn on only when something asks for them:

| Option | Environment | Default | |
|---|---|---|---|
| `--access-log=PATH` | `WEB_SERVER_ACCESS_LOG` | off | `-` writes to stdout; any other value appends to that file. Unset leaves logging off. Setting it also turns counters on |
| `--access-log-format=FMT` | `WEB_SERVER_ACCESS_LOG_FORMAT` | combined | `common`, `combined` or `json` |
| `--metrics=MODE` | `WEB_SERVER_METRICS` | off | `/metrics` in Prometheus text. `MODE` is `off`, `localhost` or `on`. A bare `--metrics` means `localhost`. Any mode other than `off` also turns counters on |
| `--no-metrics` | | | leave `/metrics` disabled |
| `--healthz` | `WEB_SERVER_HEALTHZ` | off | GET and HEAD `/healthz` answer `ok` (and counters turn on). Off: a document-root file at `/healthz` is served |
| `--no-healthz` | | | leave `/healthz` to the document root |

The admin TUI is the other switch. `EmbeddedServerControl.status()` calls `server.metricsSnapshot()`,
which is `armMetrics().snapshot()`. The first call replaces `NONE` with a recording observer; the next
request on each transport starts sampling the clock and updating counters. That snapshot does not
enable `/healthz` or `/metrics` — those stay at the flags above — so a document-root `/healthz` file
is still served after the TUI has armed the counters. Reading `server.metrics()` without
`metricsSnapshot()` does not arm anything; it returns the empty `NONE` registry.

```java
Snapshot snap = server.metricsSnapshot(); // arms on the first call, then reads
snap.requests();
```

Request threads never write the log and never take a lock for it. Each response updates striped `LongAdder`s (status, protocol, a latency bucket, bytes). When logging is on, the thread formats one `byte[]` and enqueues it on a bounded lock-free queue; a single `access-log` thread writes and flushes. A full queue drops the line and counts it (`accessLogDropped` / `sjws_access_log_dropped_total`) instead of blocking the request.

**Access line.** Common and combined are the NCSA formats, with the duration in microseconds appended. A zero body is `-`. Combined adds the quoted Referer and User-Agent. JSON is one object per line: `time` (UTC), `remote`, `method`, `path` (the request-target), `protocol`, `status`, `bytes`, `duration_us`, `referer`, `user_agent`. Quotes and controls in a CLF field are replaced with `?`. The client address is the numeric IP captured at accept (no reverse DNS).

**Metrics API** (what an admin TUI reads; also rendered at `/metrics` when that endpoint is enabled):

```java
ServerMetrics metrics = server.metrics();
Snapshot snap = metrics.snapshot();
snap.requests();                  // responses so far
snap.status(200);                 // by status code
snap.protocol("HTTP/1.1");        // HTTP/1.0, HTTP/1.1, HTTP/2, HTTP/3, or other
snap.bytesSent();                 // response body bytes (not headers)
snap.activeConnections();         // HTTP/1.1 being served + established HTTP/3
snap.http1Connections();
snap.http3Connections();
snap.accessLogDropped();
snap.latency().count();
snap.latency().sumNanos();
snap.latency().maxNanos();
snap.latency().meanNanos();
snap.latency().upperBoundNanos(); // inclusive; the last bound is Long.MAX_VALUE
snap.latency().counts();          // one bucket, not cumulative
snap.latency().cumulative(i);     // Prometheus-style, through bucket i
metrics.prometheus();             // text exposition 0.0.4
```

Status and protocol are counted separately (not as a cross product) so the hot path stays a pair of array increments. `bytes` is the body size the handler committed to; a write that throws counts the request and 0 bytes. `/healthz` and `/metrics` are counted like any other response once those probes are enabled. With observability off, nothing is counted.

**Latency histogram contract.** `sjws_http_request_duration_seconds` is a fixed log2 scale so a cluster can merge nodes by adding bucket `i` to bucket `i` (do not re-bin). Bounds are inclusive and stable: 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192 and 16384 ms, then everything above. `Snapshot.latency().counts()` is not cumulative; index `i` is `(previous bound, upperBoundNanos()[i]]`. `upperBoundNanos()` is `ServerMetrics.LATENCY_BOUNDS_NANOS` (the last entry is `Long.MAX_VALUE`). `ServerMetrics.LATENCY_LE_LABELS` is the Prometheus `le` for that same index: `0.001`, `0.002`, `0.004`, `0.008`, `0.016`, `0.032`, `0.064`, `0.128`, `0.256`, `0.512`, `1.024`, `2.048`, `4.096`, `8.192`, `16.384`, `+Inf`. The `/metrics` histogram is cumulative at those labels; adding two nodes' series with the same `le` is the merged cumulative. Changing a bound or a label is a breaking change for that merge.

`/metrics` on `localhost` answers only a loopback peer (403 otherwise), so it stays safe on a public bind. `on` answers every client. Behind a proxy that connects from 127.0.0.1, `localhost` is the proxy, not the browser. The endpoint is not a document-root file: when it is off, a file named `metrics` is served normally.

The hook is `RequestObserver`, held in a shared `ObserverSlot`. While the slot is `NONE` the transports do not call `intercept` or `complete`. When it is a recording observer they call `intercept` only if `/healthz` or `/metrics` is enabled, and `complete` after the response is written. `core.Handler` is the seam a middleware chain will decorate; that chain is not composed in the server yet, so the calls stay at the two transports and can move into one decorator later without changing the counters or the log.

**HTTP/1.1 connections** run on blocking `SocketChannel`s, one virtual thread each. One timer thread
enforces every deadline (no per-socket timeouts): `--idle-timeout` between requests,
`--header-timeout` from the first byte of a request until its head (and any small body to discard,
also after a `100 Continue`) has arrived (then `408` and close: slowloris), and `--write-timeout` without progress while a response
is being written (a client that stops reading is closed; a slow download that keeps moving is not).
Files larger than 64 KiB that are not in the cache go out with `sendfile` (`FileChannel.transferTo`);
a file that shrinks during the transfer ends the connection (the response can't be completed).
An idle keep-alive connection holds a 256 B input buffer (2 KiB only while a request needs it) and no output buffer.

**Per-address caps behind a reverse proxy or NAT.** The caps count connections per client address
(IPv4: the full address; IPv6: its /64 prefix, since one host can use any address of its /64). Behind a
reverse proxy, load balancer or a shared NAT every client arrives from the same address, so the whole
server is then limited to the per-address cap (256 HTTP/1.1 connections, 16 QUIC connections): raise
`--max-connections-per-ip` / `--h3-max-connections-per-ip` there (e.g. to `--max-connections` /
`--h3-max-connections`).

Defaults, then the config file, then arguments, then environment variables.
A bad value in the file is an error (exit 64). Invalid values of the server flags
above are still replaced by the default with a `WARN:` line; unknown options warn too.
Exit codes: 0 = normal / `--help`, 1 = cannot listen on the port (also: already running,
pid file or log file not writable, background start failed), 2 = document root missing,
64 = configuration error (e.g. `--cert` without `--key`, a bad config file, unreadable
key password file, `--stop` combined with `--daemon`). Routing, middleware and
access rules are file-only and are validated into the snapshot. The file's middleware
list is `core.MiddlewareSpec` and is not passed to `RequestFilters`, so it does not
build a chain; a caller that has bound factories can still pass `RequestFilters` to
`SjwsServer.start`. This server does not proxy or enforce the access list. ACME HTTP-01
on the main listener is answered before the file handler hides dotfiles. Reload re-reads
the file through `ReloadHook` (see docs/config.md, including the ConfigStore notes).
ACME HTTP-01 issuance uses the staging CA unless you select production.
`--status` uses 0 = running, 1 = pid file is stale, 3 = not running.

### Running in the background

`--daemon` starts a second JVM and exits once that process is listening. The child runs in a new
session when `setsid` is available (`/usr/bin/setsid`), ignores `SIGHUP`, writes the pid file and
appends stdout and stderr to the log file (default `sjws_nettask.log` in the working directory).
JVM flags placed before `-jar` or the main class, such as `-Xmx`, are kept. Paths in `--pid-file`
and `--log-file` are relative to the working directory; a service should use absolute paths.

```
java -jar target/sjws_nettask-0.2-SNAPSHOT.jar --daemon --pid-file=/run/sjws_nettask.pid \
    --log-file=/var/log/sjws_nettask.log 8080 /var/www/html
java -jar target/sjws_nettask-0.2-SNAPSHOT.jar --status --pid-file=/run/sjws_nettask.pid
java -jar target/sjws_nettask-0.2-SNAPSHOT.jar --stop --pid-file=/run/sjws_nettask.pid
java -jar target/sjws_nettask-0.2-SNAPSHOT.jar --restart --pid-file=/run/sjws_nettask.pid \
    --log-file=/var/log/sjws_nettask.log 8080 /var/www/html
```

`--stop` sends SIGTERM and waits for `--shutdown-timeout` plus 5 seconds, then SIGKILL.
`--status` prints `running pid=<n>`, `stopped`, or `stopped stale pid=<n>`. A pid file whose
process is gone, or whose pid was reused (the start time no longer matches), is stale: `--stop`
removes it and does not signal the new process. A file a live server has locked is left in place,
so a restart cannot delete the pid file the new process just claimed. `--restart` is `--stop`
followed by `--daemon`.

SIGTERM and SIGINT (the shutdown hook) stop accepting on every listener, drop idle and
half-read HTTP/1 connections immediately, and let responses already in progress finish until
`--shutdown-timeout` (default 10 seconds). Whatever is still open is then closed. HTTP/3 sends
GOAWAY and drains for up to 2 seconds in parallel with that wait. A foreground process with
`--pid-file` writes the same file, so `--stop` works for it too; without `--log-file` it keeps
logging to the console.

`contrib/sjws` is a small start/stop/status/restart wrapper (`SJWS_JAR`, `SJWS_PID_FILE`,
`SJWS_LOG_FILE`, `JAVA`). `contrib/sjws_nettask.service` is a systemd `Type=forking` example
(`PIDFile` plus `--daemon`). Copy `target/lib/` next to the jar: `java -jar` loads Netty from the
manifest `Class-Path`. On Windows, `--stop` ends the process without a POSIX SIGTERM, so the
drain hook does not run; use a service manager that can signal the JVM there.
The daemon child ignores `SIGHUP`. `ReloadHook.run()` is the reload entry a handler can call.

### HTTP/2

HTTP/2 is opt-in and runs on its **own TCP port**, next to HTTP/1.1 (whose listener and connection
handling are unchanged by it), serving the same files through the same handler.

| Option | Environment | Default | |
|---|---|---|---|
| `--h2` / `--no-h2` | `WEB_SERVER_H2` (`true/false/1/0/yes/no/on/off`) | off | enable HTTP/2 over TLS (ALPN `h2`, falling back to `http/1.1` for clients that don't offer it) |
| `--h2-port=N` | `WEB_SERVER_H2_PORT` | 8443 | TCP port of the TLS listener; `0` = ephemeral |
| `--h2c-port=N` | `WEB_SERVER_H2C_PORT` | off | cleartext HTTP/2 port: prior knowledge or `Upgrade: h2c`, otherwise HTTP/1.1; needs `--h2` |

* The certificate options (`--cert`, `--key`, `--key-password-file`, below) are shared with HTTP/3;
  without them a self-signed `localhost` certificate is generated.
* Limits (`sjws_nettask.h2.Http2Server.Limits`): 100 concurrent streams per connection
  (`SETTINGS_MAX_CONCURRENT_STREAMS`, extra streams get `REFUSED_STREAM`), 16 KiB header list
  (`SETTINGS_MAX_HEADER_LIST_SIZE`), at most 100 `RST_STREAM` per 10 s from a client (rapid reset,
  CVE-2023-44487: `GOAWAY ENHANCE_YOUR_CALM` and close), at most 16 consecutive small `CONTINUATION`
  frames (CONTINUATION flood: `GOAWAY` and close), 4096 requests in flight and 512 file responses
  streaming server-wide, `--max-connections` / `--max-connections-per-ip`, the TLS handshake within
  `--header-timeout`, idle connections closed after `--idle-timeout`, and a stalled stream (peer
  window exhausted) reset after `--write-timeout`. File bodies are sent at the pace of the peer's
  flow-control windows, one 64 KiB chunk per stream in memory at most.
* Quick check: `curl -k --http2 https://127.0.0.1:8443/` (h2), `curl --http2-prior-knowledge
  http://127.0.0.1:<h2c-port>/` (h2c).

### HTTP/3

HTTP/3 (QUIC, UDP) is opt-in and runs next to HTTP/1.1, serving the same files.

| Option | Environment | Default | |
|---|---|---|---|
| `--h3` / `--no-h3` | `WEB_SERVER_H3` (`true/false/1/0/yes/no/on/off`) | off | enable HTTP/3 |
| `--h3-port=N` | `WEB_SERVER_H3_PORT` | same as TCP port | UDP port; `0` = ephemeral |
| `--h3-host=ADDR` | `WEB_SERVER_H3_HOST` | `0.0.0.0` | UDP bind address |
| `--h3-max-connections=N` | `WEB_SERVER_H3_MAX_CONNECTIONS` | 1024 | concurrent QUIC connections (counted once the handshake completes) |
| `--h3-max-connections-per-ip=N` | `WEB_SERVER_H3_MAX_CONNECTIONS_PER_IP` | 16 | QUIC connections from one client address (IPv6: one /64); extra ones are closed with `H3_EXCESSIVE_LOAD` |
| `--h3-min-rate=B` | `WEB_SERVER_H3_MIN_RATE` | 2048 | minimum send rate per connection, bytes/s, enforced only while file slots are contended; `0` = off |
| `--cert=PATH` | `WEB_SERVER_TLS_CERT` | self-signed | certificate chain, PEM |
| `--key=PATH` | `WEB_SERVER_TLS_KEY` | self-signed | private key, PKCS#8 PEM |
| `--key-password-file=PATH` | `WEB_SERVER_TLS_KEY_PASSWORD_FILE` | | file containing the key password (one trailing newline is ignored) |
| | `WEB_SERVER_TLS_KEY_PASSWORD` | | key password itself (wins over the file) |
| `--alt-svc` / `--no-alt-svc` | `WEB_SERVER_ALT_SVC` | off | send `Alt-Svc` on HTTP/1.1 responses (see below) |
| `--alt-svc-max-age=S` | `WEB_SERVER_ALT_SVC_MA` | 86400 | `ma` of the `Alt-Svc` header |
| `--acme` / `--no-acme` | `WEB_SERVER_ACME` | off | ACME (RFC 8555) certificate issuance; default CA is Let's Encrypt staging |
| `--acme-directory=URL` | `WEB_SERVER_ACME_DIRECTORY` | staging | `staging`, `production`, or an ACME directory URL |
| `--acme-email=ADDR` | `WEB_SERVER_ACME_EMAIL` | | account contact email (required when ACME is on) |
| `--acme-domain=NAME` | `WEB_SERVER_ACME_DOMAINS` | | domain (repeat the flag; the variable is comma-separated) |
| `--acme-storage=PATH` | `WEB_SERVER_ACME_STORAGE` | `./acme` | account key, domain key and certificate chain |
| `--acme-http-port=N` | `WEB_SERVER_ACME_HTTP_PORT` | 0 | HTTP-01 port; 0 serves challenges on the main listener |
| `--acme-accept-terms` | `WEB_SERVER_ACME_ACCEPT_TERMS` | off | agree to the CA terms of service (required when ACME is on) |
| `--acme-renew-before=DUR` | `WEB_SERVER_ACME_RENEW_BEFORE` | 30d | renew this long before the certificate expires |

* `--cert` and `--key` go together; without both, a self-signed `localhost` certificate is generated.
* The key password is deliberately **not** accepted as a command-line argument (it would show in
  `ps`); it never appears in logs (`ServerConfig.toString()` masks it).
* HTTP/3 options given without `--h3` produce a warning and have no effect.
* **Reaching HTTP/3.** This server's TCP listener speaks plain HTTP/1.1 (`http://`). HTTP/3 is always
  `https`, so clients reach it directly when they know the UDP port:
  `curl --http3-only -k https://host:<udp port>/` (`-k` for the self-signed certificate).
  Browsers find HTTP/3 only through `Alt-Svc` received **over HTTPS** from a certificate they trust;
  they ignore `Alt-Svc` on `http://` origins. Browser upgrade therefore needs a real HTTPS (TLS over
  TCP) listener plus a trusted certificate, which this server does not have yet.
* **`Alt-Svc` is opt-in** (`--alt-svc`), and starting with it prints a warning saying the above. When
  enabled, and only once the UDP listener has actually bound, every HTTP/1.1 response (including
  errors and `HEAD`) carries `Alt-Svc: h3=":<real udp port>"; ma=<max-age>`; it is withdrawn on
  shutdown. If HTTP/3 cannot start (port busy, QUIC natives missing for this platform, bad
  certificate) the server logs `WARN: HTTP/3 not started, serving HTTP/1.1 only (no Alt-Svc)` and
  keeps serving HTTP/1.1. Mind that clients cache `Alt-Svc` for `ma` seconds, and that it names only a
  port: behind NAT/Docker, publish the UDP port too.
* **Limits** (per server unless noted): in-flight HTTP/3 requests = `--max-connections` (more are
  refused with `H3_REQUEST_REJECTED`, which clients may retry); once more than half of them are in use,
  a connection with 16 or more in flight gets no more (fair share: a few connections can't take the
  whole cap). Files being sent = min(`--max-connections`, 1024), at most 16 per QUIC connection, waiting
  at most `--header-timeout` for a slot; 64 concurrent request streams and 16 KiB / 1 MiB receive
  windows per stream / connection (request bodies are discarded); request field sections up to 16 KiB
  (larger: 431).
* **Handshakes.** A QUIC connection counts against `--h3-max-connections` and the per-address cap only
  once its handshake completes (its address is then validated). Until then it is *half-open*: at most
  256 at a time (more are dropped silently), and one not established within 5 s is dropped. No QUIC
  Retry is used, so the RFC 9000 anti-amplification limit applies until the handshake validates the
  address; a flood of spoofed Initial packets (~50/s) can keep the half-open table full, which delays
  *new* clients (established connections are unaffected).
* **Slow readers.** A response that makes no progress for `--write-timeout` is reset. While more than
  75% of the file slots are in use, a connection whose responses together send less than
  `--h3-min-rate` bytes/s (averaged over `--write-timeout`) also has its file responses reset, so
  slow-reading clients can't keep the slots; with free slots slow clients are left alone.
* **Shutdown** sends `GOAWAY`, lets in-flight HTTP/3 responses finish for up to 2 s, then closes.
* The self-signed certificate is new on every start (its fingerprint is printed): a pinned fingerprint
  is valid until restart, although Alt-Svc tells clients to remember the endpoint for `ma` seconds.
* With `--cert`/`--key`, startup warns (`WARN:`) if the key file is readable by group or others, or if the
  certificate is expired or not yet valid. The key password is only read when `--h3` is on.
* The QUIC transport is Netty's `netty-codec-http3` with its bundled native quiche library
  (linux x86_64/aarch64, macOS, Windows x86_64). On other platforms, or if the native library cannot be
  extracted (e.g. `/tmp` mounted `noexec`; set `-Dio.netty.native.workdir=<dir>`), `--h3` takes the
  fallback path above and the warning names the cause.
* Quick check: with `--h3 --alt-svc`, `curl -I http://127.0.0.1:<port>/` shows the `Alt-Svc` header, and
  `curl --http3-only -k https://127.0.0.1:<udp port>/` fetches over HTTP/3 (`-k` for the self-signed cert).

### Platform notes

* Names are matched as the file system matches them: on case-insensitive file systems (macOS default,
  Windows) `/HELLO.TXT` serves `hello.txt`; on Linux it is a 404. Unicode NFC/NFD variants likewise
  follow the file system. Containment and the dotfile rule hold either way.
* `%2F` is decoded before path resolution, so it acts as `/` (it cannot escape the root).
* Where the file system has no file keys (Windows), the "file was swapped between resolve and send"
  check falls back to the size check during transfer.
* Files are opened relative to the document root one directory at a time without following symlinks
  (`SecureDirectoryStream`, i.e. `openat`, on Linux and macOS), so a directory swapped for a symlink
  between resolve and send is never followed. Where that API is missing (Windows; startup then prints a
  `WARN:` line), the server opens the path without following a final symlink and re-checks its real
  path (containment) and file key afterwards; that narrows but does not close the race, so don't serve
  a tree that untrusted users can write to on such a platform.
* **Unlistable directories.** Opening relative to a directory needs read permission on it. Files below
  a directory the server can search but not read (mode `--x`, e.g. `711`), or below such a root, are
  therefore answered **403** by default. `--allow-unlistable-dirs` serves them through the path-based
  open described above (startup prints a `WARN:`). That is **weaker against local writers**: anyone who
  can write in the document root can create their own `--x` directory and race a symlink swap below it
  to get files from outside the root served. Use it only if no untrusted user can write in the root.
* Only regular files are served: a FIFO, device or socket is refused (404) after a type check made
  without following links, also when it was swapped in after the request was resolved. A narrow window
  remains between that check and the open (Java can't open non-blocking): someone with write access
  in the document root who swaps a FIFO in at exactly that moment, e.g. in a tight rename loop, can
  block the thread serving that request until the FIFO is opened for writing. If the file can't be
  opened after the response was decided (removed, swapped, permissions changed), the client gets a
  regular 404/403/500 response instead of a dropped connection.

Serves GET and HEAD, and OPTIONS (`200` with `Allow: GET, HEAD, OPTIONS`, also for `OPTIONS *`); other
known methods get 405, unknown ones 501. HTTP/1.0 and 1.1 with keep-alive and pipelining; request bodies
(`Content-Length` or `chunked`) are read and discarded, `ETag` / `If-None-Match` / `If-Modified-Since`, single byte ranges
(`Range` / `If-Range`), directory listings, and `index.html`.
Responses carry the RFC 9110 reason phrase for every registered status code (plus 429); an unregistered
code gets an empty phrase (`HTTP/1.1 599 `) and an error page titled with the bare code. File types come
from the extension (`core.Mime`; unknown: `application/octet-stream`), with `charset=utf-8` on `text/*`,
JavaScript, JSON, SVG, XML and web manifests. Dotfiles and symlinks leading outside the root are never served.

### Caching, validators and ranges

* **Small-file cache.** Files up to 256 KiB are kept in memory (both protocols), up to
  `--cache-bytes` in total (body + key + ~256 bytes per entry) and 4096 entries; eviction is CLOCK
  (second chance: an approximation of least recently used, O(1) amortized, hits take no lock). The key is the decoded, normalized path without the query, so `?v=1`, `%68ello`, `./`
  spellings share one entry and can't push others out; 404s are never cached. **Every hit stats the
  file** and compares file key (inode), mtime in ns, size and ctime; any change drops the entry and
  the request is resolved again from scratch (containment, no-follow open). Unless
  `--allow-unlistable-dirs` is set, a hit also checks (`access(2)`) that the document root and every
  directory down to the file are still readable, so a directory made search-only after the file was
  cached answers `403` at once, as it would uncached; that is one more syscall per directory level on
  every hit (measured below). A file is read through the
  same no-follow open as when it is sent, and is cached only if it did not change while being read.
  At most `min(--cache-bytes, 4 MiB)` of files are being read into memory at once; a miss that finds no
  room is served from disk without caching (streamed), and concurrent misses of one file share a
  single read.
  Limits: on a platform without file keys and ctime (Windows) an edit that keeps both size and mtime
  is not noticed; on Linux, file timestamps tick every few ms, so a same-size rewrite that also
  restores the mtime within one tick of the previous change is not noticed either. Directory listings
  are not cached. Files below unreadable (`--x`) directories, HEAD requests and 304s never load an
  entry (they don't open the file), so they behave as without a cache.
* **ETag** is `"<inode hash>-<mtime ns>-<size>"` (hex). A file version first seen less than 1 s after its
  mtime gets a weak tag (`W/"..."`) and keeps it while unchanged: on file systems with a coarse mtime a
  second write within that second could otherwise keep the tag. Up to 4096 such young versions are
  remembered; when that memory is reset, versions that are old by then get their strong tag, on the
  cached and the uncached path alike. `If-None-Match` (lists, `*`, weak
  comparison) takes precedence over `If-Modified-Since`; a 304 carries `ETag`, `Last-Modified` and
  `Date` only.
* **Ranges** (both protocols, decided in core after the 304 conditionals). One `bytes=` range
  (`a-b`, `a-`, `-n`) gets `206` with `Content-Range` (from the cache: a slice; otherwise the file region,
  with `sendfile` above 64 KiB). Several ranges (no multipart), other units, malformed or > 18-digit
  positions are ignored (`200`, whole file); a range starting at or past the end, or `-0`, gets `416` with
  `Content-Range: bytes */<size>`. `If-Range` needs a strong match: the exact current ETag, or a date
  equal to `Last-Modified` while the ETag is strong (a version under 1 s old has a weak ETag, so a resume
  of it restarts with `200`); anything else serves the whole file. Only GET has range semantics
  (RFC 9110 §14.2): HEAD with `Range` answers the plain `200` head; listings, redirects and errors ignore
  `Range` and say `Accept-Ranges: none`. Dates are parsed in IMF-fixdate form only (an RFC 850/asctime
  `If-Range` or `If-Modified-Since` is treated as not matching). Empty files ignore `Range`.

## Admin console

A small terminal console (plain ANSI, no extra dependency) runs the server in-process and lets an
admin see its status and configuration, tail logs, and start, stop or reload it:

    java -cp "target/sjws_nettask-0.2-SNAPSHOT.jar:target/lib/*" sjws_nettask.admin.AdminMain [server arguments]

It takes the same arguments and environment as the server, plus its own: `--log-file=PATH` (a file
shown in the logs view, e.g. redirected output), `--no-color`, `--no-autostart`. Commands (letter +
Enter): `s` start, `x` stop, `r` reload (re-reads arguments and environment; an invalid config
leaves the server running, a config that fails to bind restores the previous one), `v`/`c`/`l`
status/config/logs views, Enter refresh, `h` help, `q` quit (stops the server). The console talks to
the server only through `sjws_nettask.admin.ServerControl`, so another implementation (e.g. one
controlling a daemon) can be plugged in.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for the layers, the `core.Handler` seam and the request flow.

* **core** (`sjws_nettask.core`): protocol-neutral `Request`/`Response`/`Body` records, the
  `StaticFileHandler` (every status decision: path decoding and containment, index/listings, MIME,
  validators, conditionals, ranges), `SafeOpen` (no-follow, `openat`-based opens checked against the
  resolved file identity), the small-file `FileCache` and the per-address connection counter. Both
  transports call the same handler, so behaviour is identical by construction; a parity test compares
  every HTTP/3 response with the HTTP/1.1 bytes for the same request. Optional request filters
  (`RequestFilters`, see [docs/filters.md](docs/filters.md)) wrap whatever `Handler` is terminal
  (the static file handler, or a router when one is installed) on every transport. With none
  registered, and no other terminal, the static handler is called directly. `Request` carries the
  client address, whether the connection is TLS, and the raw header map.
* **HTTP/1.1** (`sjws_nettask` + `sjws_nettask.http1`): one virtual thread per connection on a blocking
  `SocketChannel`; an incremental, strict byte-level head parser (CL/TE smuggling, limits, obs-fold,
  Host, Expect); responses written whole (head and small bodies in one `write`, `Date` formatted once
  per second); files over 64 KiB that are not cached go out with `FileChannel.transferTo` (`sendfile`).
  A single timer thread enforces the idle, request-head (408) and write-progress deadlines and ends
  expired connections with `shutdown(2)` (it never blocks in `close()`).
* **HTTP/3** (`sjws_nettask.h3`, `sjws_nettask.tls`): Netty QUIC (quiche); a virtual thread per request
  stream runs the shared handler; file slots, a per-connection in-flight share, a minimum send rate
  under contention, handshake and half-open caps, GOAWAY drain.

**Measured** (loopback, 8 cores: server pinned to 4, `wrk -t4` to the other 4; 8 s per point, fresh JVM
with G1 and a 1 GB heap, 3 repetitions, medians; requests/s, or GB/s for 50 MB with a 30 s wrk timeout).
Each column is the server as of that step, all measured in the same session:

| File | Connections | before S3 | S3 (Date, cache, ETag) | S4 (channels, timer thread, sendfile) |
|---|---|---|---|---|
| 1 KB | 100 | 68k | 85k | 207k |
| 1 KB | 1000 | 54k | 67k | 218k |
| 100 KB | 100 | 43k | 60k | 69k |
| 100 KB | 1000 | 35k | 45k | 68k |
| 50 MB | 100 | 11.2 GB/s | 10.9 GB/s | 17.3 GB/s |
| 50 MB | 1000 | 11.3 GB/s | 11.3 GB/s | 16.6 GB/s |

Tails at 1000 connections: p99 as the median over runs (worst run in brackets), and the runs where wrk
reported socket timeouts (all were read timeouts: wrk's default 2 s; never connect errors). The last
two columns are the final code with the old and the new accept backlog, 7 runs each (50 MB: 3):

| File | before S3 | S3 | S4 | backlog 50 | backlog 1024 (default) |
|---|---|---|---|---|---|
| 1 KB p99 | 69 ms (687) | 46 ms (334) | 13 ms (612) | 14 ms (549) | 8.8 ms (25) |
| 1 KB timeouts | 1 of 3 runs (121) | 1 of 3 (3) | 1 of 3 (88) | 3 of 7 (65-127), one run at 5k req/s | 0 of 7 |
| 100 KB p99 | 55 ms (126) | 112 ms (623) | 64 ms (615) | 78 ms (1030) | 23 ms (40) |
| 100 KB timeouts | 0 of 3 | 0 of 3 | 1 of 3 (126) | 1 of 7 (39) | 0 of 7 |
| 50 MB p99 (30 s timeout) | 7.0 s | 6.8 s | 5.3 s | 5.0 s (17.5 GB/s) | 5.0 s (18.3 GB/s) |

Throughput at 1000 connections did not change with the backlog (1 KB 217k vs 219k req/s, 100 KB 68.8k vs
68.5k); the tails did. That is consistent with bursts of new connections overflowing an accept queue of 50,
whose SYN retransmits (after 1 s, then 3 s) show up as timeouts and p99 spikes. A 50 MB response at 1000 connections
takes longer than wrk's default 2 s timeout by itself (17 GB/s shared 1000 ways), so those runs use 30 s.

Memory per idle keep-alive connection: ~3.8–4.2 KB of heap after GC (measured with
`bench/footprint/run.sh` at 10k idle connections; ~6.6 KB before the idle-footprint changes, ~78 KB before S4):
a 256 B idle input buffer, the parked virtual-thread stack (~2.2 KB), no retained request head and
no output buffer. Caveats: these are
loopback numbers with the client on the same machine, small working sets that sit in the page cache
and wrk's closed-loop load (no think time); at fixed rates every variant could sustain (wrk2, 50k
req/s for 1 KB, 30k req/s for 100 KB) p50/p99 were within noise of each other (0.8-3 ms / 2-10 ms), and
the 1 KB / 100-connection p99.9 of S4 varied between 7 and 35 ms across runs (not GC; not explained
yet); two 1 KB runs with backlog 1024 were lower (137k, 185k req/s, no errors), also unexplained.
The search-only directory check on every cache hit (one `access(2)` per directory level, see the cache above)
costs, for 1 KB files at 100 connections, 224k → 200k req/s (-11%) for a file in the root and 213k
→ 157k (-26%) three directories down; with `--allow-unlistable-dirs` (no check) the difference is
within noise (-2 to -3%). System calls are slow on the measurement VM (~0.7 µs for one `access`,
~2.4 µs with 4 threads in parallel), so the share may be smaller on bare metal (not measured). Every cache hit stats the file (correctness first); skipping that stat for a 1 s window
measured +12-20% on 1 KB files and was deliberately not adopted. HTTP/3 throughput was not
benchmarked. A larger `sendfile` chunk (256 KiB, 1 MiB) was measured in S5 and moved 50 MB by
under 5%, so it stays at 64 KiB.
