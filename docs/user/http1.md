# HTTP/1.x

Serves GET and HEAD, and OPTIONS (`200` with `Allow: GET, HEAD, OPTIONS`, also for `OPTIONS *`); other
known methods get 405, unknown ones 501. HTTP/1.0 and 1.1 with keep-alive and pipelining; request bodies
(`Content-Length` or `chunked`) are read and discarded, `ETag` / `If-None-Match` / `If-Modified-Since`, single byte ranges
(`Range` / `If-Range`), directory listings, and `index.html`.
Responses carry the RFC 9110 reason phrase for every registered status code (plus 429); an unregistered
code gets an empty phrase (`HTTP/1.1 599 `) and an error page titled with the bare code. File types come
from the extension (`core.Mime`; unknown: `application/octet-stream`), with `charset=utf-8` on `text/*`,
JavaScript, JSON, SVG, XML and web manifests. Dotfiles and symlinks leading outside the root are never served.

**HTTP/1.1 connections** run on blocking `SocketChannel`s, one virtual thread each. One timer thread
enforces every deadline (no per-socket timeouts): `--idle-timeout` between requests,
`--header-timeout` from the first byte of a request until its head (and any small body to discard,
also after a `100 Continue`) has arrived (then `408` and close: slowloris), and `--write-timeout` without progress while a response
is being written (a client that stops reading is closed; a slow download that keeps moving is not).
Files larger than 64 KiB that are not in the cache go out with `sendfile` (`FileChannel.transferTo`);
a file that shrinks during the transfer ends the connection (the response can't be completed).
An idle keep-alive connection holds a 256 B input buffer (2 KiB only while a request needs it) and no output buffer.

Each connection moves through four states, and the timer thread applies the deadline of the state it is in:

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> HEAD: first byte
    HEAD --> PROCESSING: head received
    PROCESSING --> WRITING: response ready, or the handler waits for body bytes
    WRITING --> PROCESSING: request body bytes arrived
    WRITING --> HEAD: 100 Continue written for a discarded<br/>GET or HEAD body, the header timeout bounds it again
    WRITING --> IDLE: keep-alive
    IDLE --> CLOSED: idle timeout
    HEAD --> CLOSED: header timeout, 408
    WRITING --> CLOSED: no progress, or Connection close
    CLOSED --> [*]
    IDLE : IDLE
    IDLE : --idle-timeout
    HEAD : HEAD
    HEAD : --header-timeout
    PROCESSING : PROCESSING
    PROCESSING : no deadline
    WRITING : WRITING
    WRITING : --write-timeout
    WRITING : also while the handler waits for a request body
```

**Admission.** The connection cap is applied before `accept`, so clients over it wait in the kernel's
accept queue (`--backlog`); the per-address cap is applied after it.

```mermaid
flowchart TD
    q["Client connects<br/>kernel accept queue,<br/>--backlog default 1024"] --> cap{"Under --max-connections?"}
    cap -->|no| wait["Stays in the accept<br/>queue until a connection<br/>finishes"]
    cap -->|yes| ip{"Under<br/>--max-connections-per-ip<br/>for this address?"}
    ip -->|no| drop["503 Service Unavailable,<br/>Retry-After: 1, then close"]
    ip -->|yes| run["A virtual thread<br/>runs the connection<br/>under the IDLE, HEAD<br/>and WRITING deadlines"]
```

Over the per-address cap on cleartext HTTP/1.1, the accept loop writes a fixed `503 Service Unavailable` with `Retry-After: 1`, `Connection: close` and `Content-Length: 0`, then closes. The response never names the limit, the current count or the client address. Delivery is best-effort: a concurrent reset can still drop the 503. HTTP/2 and h2c connection admission stay a silent close (separate listeners, before TLS / preface).

**Per-address caps behind a reverse proxy or NAT.** The caps count connections per client address
(IPv4: the full address; IPv6: its /64 prefix, since one host can use any address of its /64). Behind a
reverse proxy, load balancer or a shared NAT every client arrives from the same address, so the whole
server is then limited to the per-address cap (256 HTTP/1.1 connections, 16 QUIC connections): raise
`--max-connections-per-ip` / `--h3-max-connections-per-ip` there (e.g. to `--max-connections` /
`--h3-max-connections`).
