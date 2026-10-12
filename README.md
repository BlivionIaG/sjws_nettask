# sjws_nettask
Simple Java Web Server

Basic multi-threaded (virtual threads) static file server. Requires Java 21+.

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
| `--idle-timeout=S` | `WEB_SERVER_IDLE_TIMEOUT` | 30 | keep-alive idle timeout (seconds) |
| `--header-timeout=S` | `WEB_SERVER_HEADER_TIMEOUT` | 10 | time to receive a complete request head (seconds) |
| `--max-connections-per-ip=N` | `WEB_SERVER_MAX_CONNECTIONS_PER_IP` | 256 | HTTP/1.1 connections from one client address (IPv6: one /64); extra ones are closed at once without a response |
| `--allow-unlistable-dirs` | `WEB_SERVER_ALLOW_UNLISTABLE_DIRS` | off | serve files below directories the server can't read (mode `--x`) through a weaker path-based open; see Platform notes |
| `--write-timeout=S` | `WEB_SERVER_WRITE_TIMEOUT` | 30 | HTTP/3: reset a response that makes no progress for this long; also the window of the minimum send rate. Not used by HTTP/1.1 yet |
| `-h`, `--help` | | | print usage and exit |

**Per-address caps behind a reverse proxy or NAT.** The caps count connections per client address
(IPv4: the full address; IPv6: its /64 prefix, since one host can use any address of its /64). Behind a
reverse proxy, load balancer or a shared NAT every client arrives from the same address, so the whole
server is then limited to the per-address cap (256 HTTP/1.1 connections, 16 QUIC connections): raise
`--max-connections-per-ip` / `--h3-max-connections-per-ip` there (e.g. to `--max-connections` /
`--h3-max-connections`).

Environment variables override arguments. Invalid values are replaced by the default
with a `WARN:` line; unknown options warn too. Exit codes: 0 = normal / `--help`,
1 = cannot listen on the port, 2 = document root missing, 64 = configuration error
(e.g. `--cert` without `--key`, unreadable key password file).

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

Serves GET and HEAD (other known methods: 405, unknown: 501), HTTP/1.0 and 1.1
with keep-alive and pipelining, `If-Modified-Since`, directory listings, and
`index.html`. Dotfiles and symlinks leading outside the root are never served.
