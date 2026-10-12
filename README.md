# sjws_nettask
Simple Java Web Server

Basic multi-threaded (virtual threads) static file server. Requires Java 21+.

Build and test:

    mvn verify      # tests + JaCoCo coverage gate (report: target/site/jacoco/index.html)

Run:

    java -jar target/sjws_nettask-0.2-SNAPSHOT.jar [port] [html folder] [options]

| Option | Environment | Default | |
|---|---|---|---|
| `[port]` | `WEB_SERVER_PORT` | 8080 | TCP port |
| `[html folder]` | `WEB_SERVER_DIR` | `./HTML` | document root (must exist) |
| `--max-connections=N` | `WEB_SERVER_MAX_CONNECTIONS` | 2048 | concurrent connections; extra ones wait in the backlog |
| `--idle-timeout=S` | `WEB_SERVER_IDLE_TIMEOUT` | 30 | keep-alive idle timeout (seconds) |
| `--header-timeout=S` | `WEB_SERVER_HEADER_TIMEOUT` | 10 | time to receive a complete request head (seconds) |
| `-h`, `--help` | | | print usage and exit |

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
| `--h3-max-connections=N` | `WEB_SERVER_H3_MAX_CONNECTIONS` | 1024 | concurrent QUIC connections |
| `--cert=PATH` | `WEB_SERVER_TLS_CERT` | self-signed | certificate chain, PEM |
| `--key=PATH` | `WEB_SERVER_TLS_KEY` | self-signed | private key, PKCS#8 PEM |
| `--key-password-file=PATH` | `WEB_SERVER_TLS_KEY_PASSWORD_FILE` | | file containing the key password (one trailing newline is ignored) |
| | `WEB_SERVER_TLS_KEY_PASSWORD` | | key password itself (wins over the file) |
| `--alt-svc-max-age=S` | `WEB_SERVER_ALT_SVC_MA` | 86400 | `ma` of the `Alt-Svc` header |

* `--cert` and `--key` go together; without both, a self-signed `localhost` certificate is generated.
* The key password is deliberately **not** accepted as a command-line argument (it would show in
  `ps`); it never appears in logs (`ServerConfig.toString()` masks it).
* HTTP/3 options given without `--h3` produce a warning and have no effect.
* Once the UDP listener has actually bound, every HTTP/1.1 response (including errors and `HEAD`)
  carries `Alt-Svc: h3=":<udp port>"; ma=<max-age>`. If HTTP/3 cannot start (port busy, QUIC
  natives missing for this platform, bad certificate) the server logs
  `WARN: HTTP/3 not started, serving HTTP/1.1 only (no Alt-Svc)` and keeps serving HTTP/1.1.
* Caveat: browsers only honour `Alt-Svc` received over HTTPS. The TCP listener is plain HTTP, so
  browsers will not switch to HTTP/3 from it; use `curl --http3` / `--http3-only` against the UDP port.
* The QUIC transport is Netty's `netty-codec-http3` with its bundled native quiche library
  (linux x86_64/aarch64, macOS, Windows x86_64). On other platforms, or if the native library cannot be
  extracted (e.g. `/tmp` mounted `noexec`; set `-Dio.netty.native.workdir=<dir>`), `--h3` takes the
  fallback path above and the warning names the cause.
* Quick check: `curl -I http://127.0.0.1:<port>/` shows the `Alt-Svc` header, and
  `curl --http3-only -k https://127.0.0.1:<udp port>/` fetches over HTTP/3 (`-k` for the self-signed cert).

### Platform notes

* Names are matched as the file system matches them: on case-insensitive file systems (macOS default,
  Windows) `/HELLO.TXT` serves `hello.txt`; on Linux it is a 404. Unicode NFC/NFD variants likewise
  follow the file system. Containment and the dotfile rule hold either way.
* `%2F` is decoded before path resolution, so it acts as `/` (it cannot escape the root).
* Where the file system has no file keys (Windows), the "file was swapped between resolve and send"
  check falls back to the size check during transfer.

Serves GET and HEAD (other known methods: 405, unknown: 501), HTTP/1.0 and 1.1
with keep-alive and pipelining, `If-Modified-Since`, directory listings, and
`index.html`. Dotfiles and symlinks leading outside the root are never served.
