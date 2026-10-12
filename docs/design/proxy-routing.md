# Design: request routing and reverse proxy

Status: **proposal, for review before merge**. The code on `feat/proxy-routing`
(`sjws_nettask.routing.{Router, RedirectHandler, ProxyHandler}`) is a prototype; this plan
may revise it.

## 1. Goals and non-goals

Goals
- Virtual hosts, path-prefix routes, prefix rewrites, and redirects in front of the static file handler.
- A reverse proxy to HTTP/1.1 upstreams. It adds `X-Forwarded-*` and `Forwarded` headers, applies
  timeouts, and maps upstream failures to 502/504.
- One design that serves both transports (HTTP/1.1 and HTTP/3) through `core.Handler`.
- Zero cost when disabled. When enabled, no per-request allocation spikes and a bounded memory footprint.

Non-goals (for now)
- Load balancing across several upstreams, health checks, retries, circuit breaking.
- Regex or glob path matching, header- or method-based routing, and response rewriting.
- WebSocket / `Upgrade` tunnelling, HTTP/2 or HTTP/3 to upstreams, and caching proxy responses.
- TLS client certificates to upstreams.

## 2. Placement: `core.Handler` and the middleware chain

```
transport ─▶ core.Request ─▶ [filters: logging, rights, security headers…] ─▶ Router ─┬▶ ProxyHandler(upstream A)
                                                                                     ├▶ RedirectHandler
                                                                                     └▶ StaticFileHandler (fallback)
```

- `Router`, `RedirectHandler` and `ProxyHandler` are leaf `core.Handler`s. They are not filters.
  The Middleware Chain owns the filters that wrap the whole tree.
- `SjwsServer` (the composition root) builds the `Router` only when `routing.enabled`.
  Otherwise the chain ends directly at `StaticFileHandler`, exactly as today.
- Per-route filters, such as auth on `/admin`, are expressed by wrapping that route's target handler.
  This needs no Router feature. Open question: should the chain API offer `wrap(route)`, or is plain composition enough?
- The router moves no code into transports, and the `core` package does not depend on `routing`.

## 3. Route matching

A route is `{host?, prefix, rewrite?, action}`, where the action is exactly one of `proxy`, `redirect` or `static`.

- **Host.** The value is an exact name (`example.com`), a wildcard suffix (`*.example.com`, which
  matches sub-domains only), or absent (any host). Matching ignores case and strips the port
  (bracketed IPv6 is handled). The host comes from `RequestHeaders.host` (HTTP/1 `Host` or HTTP/3 `:authority`).
  Host names are lower-cased once, at config load.
- **Prefix.** The prefix must start with `/` and matches on a segment boundary: `/api` matches `/api`, `/api/…` and `/api?…`,
  but not `/apix`. Matching runs on the raw (still percent-encoded) target, so `/%61pi` does **not**
  match `/api`. That is deliberate and documented. The decoded-path resolver remains the static
  handler's job. A dot-segment target (`/api/../secret`) is rejected with 400 before routing.
  The prototype does not do this yet; it is a phase-1 fix.
- **Rewrite.** This replaces the matched prefix. `prefix=/api, rewrite=/` sends `/api/x?q` to `/x?q`. The query is kept.
- **Ordering.** Routes are tried in config order and the first match wins. With no match, the request goes to the static
  handler, or gets 404 when `static` is disabled. At load, config validation warns about routes that an earlier route
  fully shadows.
- **Scale.** Matching is a linear scan, which suits the expected tens of routes. If there are more, phase 3 groups routes
  in a host→routes map, then sorts each group by prefix length (longest first). Order stays the user-visible contract.

## 4. Redirect

`{status: 301|302|303|307|308, location, keep_path}`. With `keep_path`, the rewritten target is
appended to `location`, and the slash is de-duplicated. The location is validated at load (no CR, LF or NUL). The response
has a small text body and honours HEAD.

## 5. Reverse proxy

**Client choice.** The plan is `java.net.http.HttpClient` (JDK, no new dependency), one instance per upstream,
created at config load. It is built with HTTP/1.1, `Redirect.NEVER`, and an executor of virtual threads.
- *Alternative:* Netty's HTTP client (already on the classpath for HTTP/3) gives true zero-copy `ByteBuf`
  relay, but it would bring Netty into `routing` and the HTTP/1 path. We revisit this only if the phase-2
  benchmarks show that HttpClient is the bottleneck.

**Connection pooling.** HttpClient keeps a keep-alive pool per (scheme, host, port). The pool is
tuned with `jdk.httpclient.connectionPoolSize` and `jdk.httpclient.keepalive.timeout` (documented, and settable
in config). Sharing one client per upstream avoids handshakes on each request.

**Timeouts.** There are three:
- `connect_timeout`, default 5 s;
- `response_timeout`, which runs until the response head arrives, default 30 s;
- `idle_read_timeout`, the gap between body chunks while streaming, default 30 s.

The client's own deadlines also stay in force, such as the write timeout via `ConnectionReaper`.

**Request headers sent upstream.**
- *End-to-end request headers, as received:* `Host` handling is configurable. The default is the upstream's host, with the original
  in `X-Forwarded-Host`; `preserve_host: true` forwards the original instead.
- *Hop-by-hop headers removed:* `Connection` (and the fields it names), `Keep-Alive`, `TE`, `Trailer`, `Upgrade`,
  `Proxy-*` and `Transfer-Encoding`.
- *Added:*
  - `X-Forwarded-For`, which appends the client IP to any incoming value only if the peer is in `trusted_proxies`; otherwise it replaces it;
  - `X-Forwarded-Proto` and `X-Forwarded-Host`;
  - RFC 7239 `Forwarded: for=…;proto=…;host="…"`, with values quoted and escaped.
- *Optional:* `Via: 1.1 sjws`.

**Response headers relayed.** All headers are relayed except hop-by-hop ones and the fields the writer owns (`Content-Length`, `Date`,
`Server`, `Alt-Svc`, `Transfer-Encoding`). A header value that cannot be represented drops that one field, not the response.

**Error mapping.**

| Condition | Status |
|---|---|
| connect refused / reset / DNS failure / malformed upstream response | 502 |
| connect or response-head timeout | 504 |
| failure after the head was sent (mid-body) | abort the connection/stream (never a short 200) |
| upstream body exceeds `max_body` (buffered mode only) | 502 |
| target not origin-form, invalid method | 400 (normally caught earlier by the parser) |

Errors are logged once per upstream per interval, rate-limited so log volume stays flat when an upstream is down.

## 6. Config schema (sent to Config & ACME)

```yaml
routing:
  enabled: false                 # default; false = no Router built, zero cost
  forwarded_proto: auto          # http | https | auto (from the listener)
  trusted_proxies: []            # CIDRs whose X-Forwarded-For we extend
  upstreams:
    api:
      url: "http://127.0.0.1:9000"   # http/https, optional base path
      connect_timeout: 5s
      response_timeout: 30s
      idle_read_timeout: 30s
      max_body: 16m              # buffered-mode cap (phase 1); ignored once streaming lands
      preserve_host: false
  routes:                        # ordered, first match wins
    - host: "example.com"        # optional: exact or "*.suffix"
      prefix: "/api"             # required
      rewrite: "/"               # optional
      proxy: api                 # exactly one of proxy | redirect | static
    - prefix: "/old"
      redirect: { status: 301, location: "https://example.com/new/", keep_path: true }
    - prefix: "/"
      static: true
```

Validation at load:
- unknown upstream names, more than one action on a route, a bad prefix or rewrite, a non-redirect status, or CR/LF in a location are all errors;
- shadowed routes produce a warning.

The command line and environment get **no new flags** (config file only). This keeps `DocsConsistencyTest`'s flag table
unchanged.

## 7. Efficiency

- **Disabled:** no object is built and the chain is identical to today's. There is no branch on the hot path.
- **Router hot path:**
  - Matching scans the target once (`indexOf('?')`), then does `startsWith` and a char check per route, which allocates nothing.
  - A new `Request` is allocated only when a rewrite applies.
  - Host comparison uses `regionMatches(ignoreCase)` on the raw header, with no substring or lower-casing.
    The prototype still allocates via `stripPort` + `toLowerCase`; that is a phase-1 fix.
- **Proxy:**
  - Pooled connections avoid handshakes.
  - Phase 1 buffers up to `max_body`, which is bounded but costs a copy.
  - Phase 2 streams: the upstream `Flow.Publisher<List<ByteBuffer>>` is handed to the writer as a streaming body,
    with backpressure, so a request never holds more than one or two chunk buffers. True zero-copy (kernel splice) is out of
    reach with HttpClient. The goal is "no extra full-body copy".
- **No per-request client, executor, or header-name normalisation tables.** Static sets are used for the hop-by-hop headers.

## 8. Core contract gaps and proposed changes

Today `Request(method, target, RequestHeaders)` carries only the static handler's six headers, and
`Body` is sealed (`Empty | Bytes | FileBody`). The proxy needs four things:

| Gap | Proposed change (owned with Middleware Chain / core reviewers) |
|---|---|
| Request body | `Request.body(): RequestBody` with `RequestBody.EMPTY` default. It is a pull-style `InputStream`-like source with known/unknown length, produced by the HTTP/1 chunked/length decoder and the h3 stream. The static handler ignores it, but the transport must drain or close it if the handler does not consume it. |
| Client address | `Request.peer(): InetSocketAddress` (nullable in tests), plus `secure(): boolean` for proto. |
| Arbitrary headers | `RequestHeaders.all(): List<Map.Entry<String,String>>`, a lower-cased, ordered view filled by both transports. The existing typed fields stay as they are. |
| Streaming response body | A new `Body.Stream(long lengthOrMinus1, Source)` variant. Writers use chunked encoding (HTTP/1.1, or close-delimited for 1.0) or DATA frames (HTTP/3) when the length is unknown. An abort on source error is never a short response. |

All four are additive, with existing constructors kept, so the static path and existing tests are unchanged. They are
proposed as a separate core PR that lands before proxy phase 2.

## 9. Benchmark plan (wrk + wrk2)

The setup: the box, JDK 25, and the server jar against a local upstream (a second sjws instance, or a minimal
Netty echo server), pinned with `taskset` where possible. Every run gets a 5 s warm-up, then 3 runs of 30 s; we report the median.

1. **Baseline vs disabled:** master and the branch with `routing.enabled=false` on `/index.html`, wrk `-t4 -c64`.
   Accept if the difference is within noise (±3% req/s, p99 within ±5%).
2. **Router overhead:** 1, 10 and 50 routes, with the match on the last route, falling through to static. Target: under 2% req/s loss at 10 routes.
3. **Proxy throughput and latency:** compare a direct hit on the upstream with sjws→upstream, for 1 KB, 64 KB and 4 MB bodies.
   - wrk measures maximum req/s.
   - wrk2 (`-R` at 50% and 80% of the measured max) gives the latency percentiles (p50/p99/p99.9) without coordinated omission.
4. **Pooling check:** compare the upstream's accepted-connection count to the request count. The expected ratio is far below 1.
5. **Failure modes:** with the upstream down and with a slow upstream, measure 502/504 latency and confirm that throughput on the static route is
   unaffected (it is isolated).
6. **Memory:** heap and allocation rate (`-Xlog:gc`, JFR allocation profile) during run 3. Streaming mode must stay flat
   with 4 MB bodies.

Results go into the PR description of each phase, with the commands, so others can reproduce them.

### Phase 1 results (2026-10-12, shared 8 vCPU box, server on CPUs 0-3, wrk on 4-7)

Harness: `bench/proxy-routing/` (`run.sh`, real jar with `--config`). wrk `-t4 -c64`, 3 × 10 s after a 3 s warm-up, median req/s (p99 of that run).
The box is shared, so differences under about 15 % are noise.

| Scenario | req/s (median) | p99 |
|---|---|---|
| master dc5346c, `/index.html` | 225,718 | 2.15 ms |
| branch, routing disabled | 258,865 | 2.01 ms |
| branch, 1 route (match) | 280,318 | 3.57 ms |
| branch, 10 routes (match on last) | 284,710 | 4.04 ms |
| branch, 50 routes (match on last) | 257,274 | 1.96 ms |
| direct upstream, 1 KiB | 257,411 | 1.99 ms |
| proxied, 1 KiB | 37,892 | 4.39 ms |
| direct upstream, 64 KiB | 93,776 | 1.98 ms |
| proxied, 64 KiB | 15,957 | 9.56 ms |

wrk2 at 10k req/s, 1 KiB: direct p50 0.72 ms / p99 1.86 ms / p99.9 2.13 ms; proxied p50 1.11 ms / p99 5.38 ms / p99.9 9.14 ms.
Pooling: 68 upstream connections in total for every proxied request (64 client connections).

Reading: with routing disabled, and with the router in front of static files, throughput is within
noise of master. The proxy costs about 6.8× throughput at 1 KiB and 5.9× at 64 KiB compared with
hitting the upstream directly. That points at `HttpClient` (its own selector thread plus buffered
copies), not at routing. This is the trigger in §5/§11 Q6 to evaluate a Netty or blocking-socket
upstream client in phase 2.

## 10. Phased rollout

0. **This document.** It is reviewed and agreed with Middleware Chain and Config & ACME.
1. **Routing + buffered proxy, opt-in.** Revise the prototype (allocation-free host match, dot-segment
   rejection, config wiring in `SjwsServer`), with bodyless requests only. Proxy routes reject methods that carry a body with 501
   until phase 2. Run benchmarks 1–3.
2. **Core contract additions** (§8) as a separate PR, then request-body forwarding, `X-Forwarded-For`/`Forwarded for=`
   with `trusted_proxies`, and streaming responses. Run benchmarks 3–6.
3. **Optional:** host-indexed route table, multiple upstreams per name (round-robin), and passive health checks.

## 11. Open questions

1. Middleware Chain: are filters applied around the Router only, or should routes declare per-route filter lists?
2. Core owners: do they agree with the four additive changes in §8, and with `Body.Stream` living in `core` (writer support in both transports)?
3. Config & ACME: should the format be YAML (as above) or the existing config file format? Durations as `5s`, sizes as `16m`? Should ACME
   HTTP-01 challenges (`/.well-known/acme-challenge/`) bypass routing? The proposal is that they are matched before the Router, always.
4. Should Host default to the upstream host or be preserved? The proposal is upstream host plus `X-Forwarded-Host`, with `preserve_host` to opt in.
5. HTTP/3 clients being proxied to HTTP/1.1 upstreams: should `Alt-Svc` from upstreams always be stripped? The proposal is yes.
6. Should we keep HttpClient or move to the Netty client? We decide after the phase-2 benchmarks.
