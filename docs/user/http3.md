# HTTP/3

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
  they ignore `Alt-Svc` on `http://` origins. Browser upgrade therefore needs `Alt-Svc` on an HTTPS (TLS over
  TCP) listener with a trusted certificate; the `--h2` listener is one, but it does not send `Alt-Svc` yet.
* **How a client finds HTTP/3.** Directly, when it knows the UDP port. Through `Alt-Svc`, only if it
  received the header over HTTPS, which this server's plain HTTP/1.1 TCP listener does not offer:

  ```mermaid
  sequenceDiagram
      participant C as Client
      participant T as TCP listener<br/>plain HTTP/1.1
      participant Q as UDP listener<br/>HTTP/3

      Note over T,Q: With --h3 --alt-svc the UDP socket binds first,<br/>then Alt-Svc is advertised
      C->>T: GET / over http://
      T-->>C: 200 with Alt-Svc
      Note over C,T: h3 on the UDP port, ma=86400.<br/>Browsers ignore Alt-Svc received over http://
      C->>Q: QUIC handshake, HTTP/3
      Q-->>C: the same files as HTTP/1.1
      Note over C,Q: Direct access, for example:<br/>curl --http3-only -k https://host:udp-port/
  ```

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
  windows per stream / connection; request field sections up to 16 KiB (larger: 431).
* **Request bodies.** For methods other than GET and HEAD, DATA frames are handed to the handler as a
  pull-style request body (the swarm's `:submit`, `:stream`, `:chunk`, `:reply`, the proxy). Size
  limits are the handler's, as on HTTP/1.1: a declared `content-length` is the body's length (larger
  than the handler's limit: 413 before anything is read); without one the length is unknown and the
  handler stops at its cap. The server never buffers more than about 32 KiB per request: while the
  handler is behind, the stream stops reading and the client waits on its flow-control window. Each
  wait for body bytes is bounded by `--write-timeout`. What the handler does not read is dropped, and
  the client is asked to stop sending (STOP_SENDING with `H3_NO_ERROR`). The static file handler
  never reads bodies, so with no filter or other handler installed nothing is attached (zero cost).
* **Response field sections.** A response is never sent with a field section larger than the
  client's `SETTINGS_MAX_FIELD_SECTION_SIZE` (RFC 9114 §4.2.2): such a client would fail its whole
  connection (Netty's client does, with its 8 KiB default). The server answers that request with a
  500 instead, and the connection keeps working. Clients that expect large response fields (a swarm
  `:claim` with a large `max`, see [agent-swarm](../design/agent-swarm.md) §5.2) must advertise a
  larger value, e.g. 64 KiB.
* **Handshakes.** A QUIC connection counts against `--h3-max-connections` and the per-address cap only
  once its handshake completes (its address is then validated). Until then it is *half-open*: at most
  256 at a time (more are dropped silently), and one not established within 5 s is dropped. No QUIC
  Retry is used, so the RFC 9000 anti-amplification limit applies until the handshake validates the
  address; a flood of spoofed Initial packets (~50/s) can keep the half-open table full, which delays
  *new* clients (established connections are unaffected).

  The *connection caps* are `--h3-max-connections` and `--h3-max-connections-per-ip`. A request stream is
  *admitted* when it is under the server-wide in-flight cap, within its connection's fair share, and not
  past a GOAWAY.

  ```mermaid
  flowchart TD
      a["New QUIC connection<br/>source address not<br/>yet validated"] --> b["Fewer than 256<br/>half-open handshakes<br/>otherwise:<br/>dropped silently"]
      b --> c["Handshake completes<br/>within 5 s<br/>otherwise: dropped"]
      c --> d["Under the connection caps<br/>otherwise: closed with<br/>H3_EXCESSIVE_LOAD"]
      d --> e["A request stream is<br/>admitted<br/>otherwise: refused with<br/>H3_REQUEST_REJECTED"]
      e --> f["The handler runs on<br/>a virtual thread<br/>a file response waits<br/>for a file slot<br/>and is refused if<br/>none frees up"]
  ```

* **Slow readers.** A response that makes no progress for `--write-timeout` is reset. While more than
  75% of the file slots are in use, a connection whose responses together send less than
  `--h3-min-rate` bytes/s (averaged over `--write-timeout`) also has its file responses reset, so
  slow-reading clients can't keep the slots; with free slots slow clients are left alone.
* **Shutdown** sends `GOAWAY`, lets in-flight HTTP/3 responses finish for up to 2 s, then closes.
* The self-signed certificate is new on every start (its fingerprint is printed): a pinned fingerprint
  is valid until restart, although Alt-Svc tells clients to remember the endpoint for `ma` seconds.
* With `--cert`/`--key`, startup warns (`WARN:`) if the key file is readable by group or others, or if the
  certificate is expired or not yet valid. The key password file is only read when `--h3` or `--h2` is on.
* The QUIC transport is Netty's `netty-codec-http3` with its bundled native quiche library
  (linux x86_64/aarch64, macOS, Windows x86_64). On other platforms, or if the native library cannot be
  extracted (e.g. `/tmp` mounted `noexec`; set `-Dio.netty.native.workdir=<dir>`), `--h3` takes the
  fallback path above and the warning names the cause.
* Quick check: with `--h3 --alt-svc`, `curl -I http://127.0.0.1:<port>/` shows the `Alt-Svc` header, and
  `curl --http3-only -k https://127.0.0.1:<udp port>/` fetches over HTTP/3 (`-k` for the self-signed cert).
