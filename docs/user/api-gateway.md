# API gateway

Routes (reverse proxy, redirects, static), auth and rate limits for edge / gateway
nodes. Developer detail for the six gateway filter factories:
[gateway.md](../development/gateway.md). Routing design and results:
[proxy-routing.md](../design/proxy-routing.md). Config keys:
[config-file.md](config-file.md#routing) and [config-file.md § Middleware](config-file.md#middleware).

## Two layers

| Layer | Where | Applied when |
|---|---|---|
| **Routing** | YAML `routing:` | `routing.enabled: true` — compiled into a `Router` on every start/reload (`RoutingCompiler`). Default `enabled: false` leaves the request path unchanged. |
| **Gateway filters** | `api-key`, `jwt`, `rate-limit`, `cors`, `request-size`, `header-rewrite` | Only after something binds `GatewayFilters.install(…)` and publishes a `FilterDocument` through `SjwsServer.chainControl`. The stock daemon does **not** call `install` or `chainControl` by itself. YAML `middleware:` is **stored and not applied** on startup (a non-empty list warns); see [config-file.md](config-file.md#middleware). |

Embedders and tests wire filters like this ([gateway.md](../development/gateway.md)):

```java
var registry = GatewayFilters.install(RequestFilters.create());
var control = server.chainControl(registry, Set.of("edge"));
control.apply(FilterDocument.fromJson(json));
```

A control-plane projection for `data-role=gateway` is designed to carry routing / middleware /
access ([control-plane design](../design/control-plane.md)); the filter factories still have to
be bound on the node. Prefer `keys-file` / `hmac-keys-file` in any CP proposal (inline
`keys` / `hmac-keys` → 400).

**HTTP/2 note:** gateway filters read `request.headers().values(...)`. On HTTP/2 (h2/h2c and the
HTTP/1.1 ALPN fallback) that map is filled when `attachBodies` is on — whenever a filter chain,
metrics observe wrapper, or non-static terminal is armed. HTTP/1.1 always fills it; HTTP/3 fills
it when a chain or non-static terminal exists at startup.

## Routes (`routing:`)

```yaml
routing:
  enabled: true
  rate_limit: { by: peer, rate: 1000/s, burst: 2000 }   # optional global; before any route
  upstreams:
    api: { url: "http://127.0.0.1:9000", connect_timeout: 2s, response_timeout: 30s }
  routes:   # ordered, first match wins
    - prefix: /api
      proxy: api           # exactly one of proxy | backends | redirect | static
      rate_limit: { by: header, header: X-Api-Key, rate: 600/m, burst: 20, buckets: 4096 }
      errors: problem      # optional RFC 9457 bodies for this server's error pages
    - prefix: /login
      static: true
      rate_limit: { by: route, rate: 50/s }
    - prefix: /old
      redirect: https://example.com/new
```

- `proxy` routes share one `ProxyHandler` (connection pool) per upstream name.
- `backends` (weighted / canary / sticky) replace a single `proxy:` — see
  [config-file.md](config-file.md#routing).
- ACME challenges are answered before any route. A path with a `..` segment is 400.
- Proxied body memory bounds: `-Dsjws.proxy.maxInflightBytes` (default 64 MiB) and optional
  per-peer share. Peers are the remote socket address only (never `X-Forwarded-For`).
- Chunked request bodies (and HTTP/3 bodies without content-length) are **501** for now.

Routing `rate_limit` / global `routing.rate_limit` use a fixed GCRA stripe table (local to this
process). `by` is `peer` (default), `header`, or `route`. Over limit → 429 + `Retry-After`.

## Auth filters (when the chain is bound)

### api-key

Require `keys-file` (preferred) or local-only inline `keys`. Header default `x-api-key`;
optional `query` if the header is absent. Miss → 401 `WWW-Authenticate: ApiKey`. Keys are
SHA-256 hashed at filter build; raw material is not kept.

### jwt

Bearer JWT. `alg` allow-list (`HS256`…`ES512`; `none` rejected). HMAC via `hmac-keys-file`
(or local inline `hmac-keys`); RSA/EC via inline public SPKI (`rsa-keys` / `ec-keys` as
`kid:base64`). `exp` required unless `require-exp: false`. No remote JWK fetch; `crit` /
`jku` / `jwk` / `x5u` / `x5c` rejected. Bad token → 401 `WWW-Authenticate: Bearer`.

Full param tables: [gateway.md](../development/gateway.md).

## Rate limits (gateway `rate-limit` filter)

Same GCRA table family as routing. Params: `capacity` (burst), `refill-per-second`, `by`
(`ip` default / `header` / `route`), optional `buckets` (power of two). Refusal → configured
`status` (default 429) + `Retry-After`. Cluster-wide quota sharing is **planned** (design
proxy-routing v2); today limits are **per node**.

## Other gateway filters

| Type | Role |
|---|---|
| `cors` | Exact origins or `*`; preflight `OPTIONS` → 204/403 |
| `request-size` | `max-bytes` / `max-target`; optional reject chunked → 411 |
| `header-rewrite` | request/response set and remove lists |

Put `cors` at a lower order than `api-key` / `jwt` when browsers must preflight without
credentials.

## Example filter document (JSON)

Shape used by `FilterDocument` / `chainControl.apply` (not the YAML `middleware:` list):

```json
{
  "format": 1,
  "version": 1,
  "filters": [
    {
      "type": "cors",
      "order": 0,
      "params": { "origins": "https://app.example.com", "methods": "GET,POST,OPTIONS" }
    },
    {
      "type": "api-key",
      "order": 10,
      "params": { "keys-file": "/etc/sjws/api-keys", "header": "x-api-key" }
    },
    {
      "type": "rate-limit",
      "order": 20,
      "params": { "capacity": "100", "refill-per-second": "50", "by": "ip" }
    }
  ]
}
```

A bad param throws and the previous snapshot stays. Label selectors on entries that do not
match this node are still constructed (so bad params fail the document) then omitted from the
live chain.

## Planned

- Stock daemon auto-binding of `GatewayFilters` for `data-role=gateway` without an embedder
  (today: explicit `install` + `chainControl`, or test/bench harnesses).
- Distributed per-node quota share from the control plane (proxy-routing design v2).
- Header map on HTTP/2 for gateway filters.

## Related docs

- [command-line.md](command-line.md) · [config-file.md](config-file.md)
- [gateway.md](../development/gateway.md) · [filters.md](../development/filters.md)
- [control-plane-service.md](../operations/control-plane-service.md) · [cp-console.md](../operations/cp-console.md)
