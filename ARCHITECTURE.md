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

These are true of `master` today and are the next refactoring steps (each small, without file
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
