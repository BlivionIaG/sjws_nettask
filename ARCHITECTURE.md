# Architecture

## Current structure (master `a6a63fb`)

| Layer | Classes | Responsibility |
|---|---|---|
| Entry / config | `Sjws_nettask` (CLI), `ServerConfig` | parse arguments, validate, start |
| Lifecycle | `SjwsServer` | build the handler, bind TCP, start HTTP/3 `Transport`, Alt-Svc, shutdown |
| Connection handling | `ClientManager` (accept loop, per-IP limits, virtual threads), `ConnectionReaper` (deadlines), `ChannelOutput`, `Http1Connection` | socket lifecycle, keep-alive, timeouts, body draining |
| HTTP/1 wire format | `http1.Http1Parser`, `http1.Http1Writer`, `http1.ParseError` | bytes ⇄ `Head` / `Response` |
| HTTP/3 wire format | `h3.*` (Netty QUIC) | QUIC frames ⇄ core types |
| Core contract | `core.Request`, `RequestHeaders`, `Response`, `ResponseHeaders`, `Body` | protocol-neutral request/response |
| Application | `core.StaticFileHandler` + `PathResolver`, `SafeOpen`, `FileCache`, `ByteRanges`, `EntityTag`, `Mime` | every status decision for static files |

Data flow: `ClientManager` → `Http1Connection` → `Http1Parser` → *(mapping)* → `StaticFileHandler.handle(Request)` → `Response` → `Http1Writer`.

### Problems
1. **No handler abstraction.** Transports depend on the concrete `StaticFileHandler`, so nothing (routing, rights checks, admin/status pages, test stubs) can sit in front of it without editing every transport.
2. **Protocol mapping is inlined** in `Http1Connection.serveOne`, mixed with I/O and deadline logic, and only testable through a socket.
3. `StaticFileHandler` mixes routing-like decisions (method check, listing vs file) with file serving.
4. `SjwsServer` both wires the object graph and runs lifecycle.

## Target

```
transport (http1 / h3)  ──parse──▶ core.Request ──▶ core.Handler ──▶ core.Response ──serialize──▶
                                                    │
                                     (later) filters/decorators: rights, security headers, logging
                                                    │
                                              StaticFileHandler
```

* `core.Handler` (`Response handle(Request)`) is the single seam between transports and application logic. Cross-cutting features are **decorators** (`Handler → Handler`) composed in one place (`SjwsServer`), not edits inside transports.
* Each transport has a pure mapping step (`http1.Http1Requests`, later an h3 equivalent) unit-testable without sockets.
* `SjwsServer` becomes the composition root: config → handler chain → transports.

## Migration steps
1. **(this PR)** Add `core.Handler`; `StaticFileHandler implements Handler`; `Http1Connection` depends on `Handler`; extract `Http1Requests.toCore`. Behavior-preserving; tests for both seams.
2. Widen `ClientManager`, `Transport.Factory` and `h3` request handling to accept `Handler` (`handler()` accessors keep returning `StaticFileHandler` for compatibility).
3. Compose the handler chain in `SjwsServer` (one `Handler` built from config); sibling features (rights, security headers, admin) plug in as decorators.
4. Move the method check (405) into a small router decorator; keep file logic in `StaticFileHandler`.

Each step is small and avoids file moves/renames so parallel workstreams don't conflict.

## Observability

`observe.RequestObserver` is the isolated hook. It is not a `Handler` decorator: the chain above is owned by another change and is not composed in `SjwsServer` yet. HTTP/1.1 (`Http1Connection`) and HTTP/3 (`Http3RequestHandler`) each call `intercept` (for `/healthz` and `/metrics`) and `complete` (after the response write). When the chain exists, one decorator can make those two calls and the transport calls go away; `ServerMetrics`, `Snapshot` and `AccessLog` stay where they are.

Counters are `LongAdder`s. The access log is a bounded lock-free queue drained by one thread. Neither path blocks a request on I/O.
