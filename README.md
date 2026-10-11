# sjws_nettask
Simple Java Web Server

Basic multi-threaded (virtual threads) static file server. Requires Java 21+.

Build and test:

    mvn package

Run:

    java -jar target/sjws_nettask-0.2-SNAPSHOT.jar [port] [html folder] [options]

| Option | Environment | Default | |
|---|---|---|---|
| `[port]` | `WEB_SERVER_PORT` | 8080 | TCP port |
| `[html folder]` | `WEB_SERVER_DIR` | `./HTML` | document root (must exist) |
| `--max-connections=N` | `WEB_SERVER_MAX_CONNECTIONS` | 10000 | concurrent connections; extra ones wait in the backlog |
| `--idle-timeout=S` | `WEB_SERVER_IDLE_TIMEOUT` | 30 | keep-alive idle timeout (seconds) |
| `--header-timeout=S` | `WEB_SERVER_HEADER_TIMEOUT` | 10 | time to receive a complete request head (seconds) |

Environment variables override arguments. Exit codes: 1 = cannot listen on the
port, 2 = document root missing.

Serves GET and HEAD (other known methods: 405, unknown: 501), HTTP/1.0 and 1.1
with keep-alive and pipelining, `If-Modified-Since`, directory listings, and
`index.html`. Dotfiles and symlinks leading outside the root are never served.
