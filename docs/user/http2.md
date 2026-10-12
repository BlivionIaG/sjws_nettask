# HTTP/2

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
