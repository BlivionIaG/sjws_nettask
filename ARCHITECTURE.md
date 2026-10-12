# Architecture

How the code on `master` is organised. User-visible behaviour (options, limits, caching, ranges) is
documented in the [README](README.md) and the [configuration reference](docs/configuration.md); the
tests that pin each behaviour are listed in [tests/README.md](tests/README.md).

## Layers

| Layer | Package / classes | Responsibility |
|---|---|---|
| Entry / config | `Sjws_nettask` (CLI, exit codes), `ServerConfig` | parse arguments and `WEB_SERVER_*` variables, validate, start |
| Lifecycle | `SjwsServer`, `Transport` (+ `Transport.Factory`), `AltSvc` | build the `StaticFileHandler`, bind TCP, start the optional HTTP/3 transport, Alt-Svc, shutdown |
| HTTP/1.1 connections | `ClientManager` (accept loop, per-IP limit, one virtual thread per connection), `Http1Connection`, `ConnectionReaper` (single timer thread for all deadlines), `ChannelOutput` | socket lifecycle, keep-alive, pipelining, body draining, timeouts |
| HTTP/1.1 wire format | `sjws_nettask.http1`: `Http1Parser`, `ChunkedDecoder`, `Http1Requests`, `Http1Writer`, `ParseError` (+ legacy `HTML`, `Page`, `Header`) | bytes ⇄ `Head`; `Head` → `core.Request`; `core.Response` → bytes |
| HTTP/3 | `sjws_nettask.h3`: `Http3Server`, `Http3RequestHandler`, `Http3Writer`, `Limits`, `RateGuard`, `NoRetryTokenHandler` | Netty QUIC (quiche) ⇄ core types; file slots, fair share, min send rate, handshake caps, GOAWAY |
| TLS | `sjws_nettask.tls`: `TlsMaterial`, `SelfSignedDer` | PEM certificate/key loading (encrypted keys), self-signed certificate |
| Core contract | `sjws_nettask.core`: `Handler`, `Request`, `RequestHeaders`, `Response`, `ResponseHeaders`, `Body` | protocol-neutral request/response |
| Application | `core.StaticFileHandler` + `PathResolver`, `RequestRules`, `SafeOpen`, `FileCache`, `FileMeta`, `ByteRanges`, `EntityTag`, `Validator`, `Mime`, `HttpDate`, `DateClock`, `PerIpLimit` | every status decision for static files |
| Observability | `sjws_nettask.observe`: `RequestObserver`, `ServerMetrics`, `Snapshot`, `AccessLog` | access log, in-process metrics, `/healthz`, optional `/metrics` |

`SourceGuardTest` enforces the boundaries: no Netty in core or HTTP/1, and `h3` does not import HTTP/1.

## Request flow

```
HTTP/1.1: ClientManager → Http1Connection → Http1Parser → Http1Requests.toCore ─┐
                                                                                ├→ Handler.handle(Request) → Response
HTTP/3:   Http3Server → Http3RequestHandler (virtual thread per stream) ───────┘        │
                                                                     Http1Writer / Http3Writer
```

* `core.Handler` (`Response handle(Request)`) is the seam between transports and application logic.
  `StaticFileHandler` implements it; `Http1Connection` depends on `Handler`.
* Both transports call the same handler, so behaviour is identical by construction; a parity test
  compares every HTTP/3 response with the HTTP/1.1 bytes for the same request.
* Errors raised before a request reaches the handler (parse errors, 408 deadlines, 431 on HTTP/3) use
  `StaticFileHandler.error(...)`, so their pages match the handler's. Status lines and
  error-page titles use `StaticFileHandler.reasonPhrase` (all RFC 9110 codes plus 429, empty for
  unregistered ones); `Mime` maps extensions to `Content-Type`.

## Current gaps

These are true of this branch today and are the next refactoring steps (each small, without file
moves, so parallel workstreams don't conflict):

1. `ClientManager`, `SjwsServer`, `Transport.Factory` and `h3.Http3RequestHandler` still take the
   concrete `StaticFileHandler`; widen them to `Handler` (keeping a compatible `handler()` accessor).
2. Compose the handler chain in `SjwsServer` (one `Handler` built from config) so cross-cutting
   features (rights, security headers, admin/status pages, logging) plug in as decorators
   (`Handler → Handler`) instead of edits inside transports.
3. Move the method check (405/OPTIONS) from `StaticFileHandler` into a small router decorator.
4. HTTP/3 has no pure mapping step equivalent to `Http1Requests.toCore` yet.

## Future direction

The project's goal is a reliable, fast, low-latency message-oriented middleware that is also a web
server. Messaging is still being designed and nothing of it exists in the code yet; the `core.Handler`
seam and the protocol-neutral `Request`/`Response` contract are where such features are expected to
plug in.

## Observability

`observe.RequestObserver` is the isolated hook. It is not a `Handler` decorator: the chain above is not composed in `SjwsServer` yet. Both transports share an `ObserverSlot`. While that slot is `RequestObserver.NONE` (the default) a request does not read the clock, does not update a counter, and does not look at the path for `/healthz` or `/metrics`, so a document-root file at `/healthz` is served. `--access-log`, `--metrics` (other than `off`) and `--healthz` install a recording observer at startup. The admin TUI's status view calls `SjwsServer.metricsSnapshot()`, which arms counters on the first call without enabling `/healthz`. When the chain exists, one decorator can make the `intercept` and `complete` calls and the transport calls go away; `ServerMetrics`, `Snapshot` and `AccessLog` stay where they are.

Counters are `LongAdder`s, and only after the observer is recording. The access log is a bounded lock-free queue drained by one thread. Neither path blocks a request on I/O.

The latency histogram is a fixed log2 scale (ratio 2, from 1 ms through 16.384 s, then `+Inf`). `ServerMetrics.LATENCY_BOUNDS_NANOS` and `LATENCY_LE_LABELS` are the stable contract: a cluster merges by summing `Snapshot.latency().counts()[i]` across nodes, or by summing Prometheus `sjws_http_request_duration_seconds_bucket` series that share an `le` label. The in-process counts are not cumulative; the exposition is.
