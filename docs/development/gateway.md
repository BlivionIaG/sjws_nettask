# API gateway filters

These filters are ordinary factories on the [declarative chain](filters.md). Nothing here runs,
and none of these classes are loaded, until a caller binds them and a document names one.
They read the raw header map (`request.headers().values(...)`). It is complete on HTTP/1.1.
HTTP/3 fills it only when a chain or a non-static terminal exists at startup. HTTP/2 (h2 and h2c
streams) fills it when the live generation has `attachBodies` on — true whenever a filter chain,
metrics observe wrapper, or non-static terminal is armed — and leaves it empty on the static-only
path. Gateway filters therefore see headers such as `x-api-key` and `authorization` over h2/h2c
once a chain is live. The HTTP/1.1 ALPN fallback on the HTTP/2 TLS listener uses the same
`attachBodies` gate (#446).
`GatewayFilters.install(registry)` binds the built-in factories (`api-key`, `basic-auth`, `jwt`,
`rate-limit`, `cors`, `request-size`, and `header-rewrite`). `SjwsServer.chainControl` still does not
change the request path until the first successful `apply`.

```java
var registry = GatewayFilters.install(RequestFilters.create());
var control = server.chainControl(registry, Set.of("edge"));
control.apply(FilterDocument.fromJson(json));
```

A bad param throws and the previous snapshot stays. An entry whose label selector does not
match this node is still constructed, so the bad param fails the document, and then left out
of the snapshot. Keys and limits are fixed for the life of a snapshot. Rotation is a new
document: a request already inside the old chain finishes on the old keys.

Params are strings, the same rule as every other document entry.

## api-key

`keys` (comma-separated) or `keys-file` (path to the same material) is required; set exactly
one. Each key is hashed with SHA-256 when the filter is built; the raw key is not kept.
`keys-file` is read with the same rules as other secret files (mode 0600 or 0400, owned by this
process, leaf not a symlink). A control-plane proposal must use `keys-file`; inline `keys` is
refused with 400. Local config may still use inline `keys` (a WARN points at the file form).
`header` defaults to `x-api-key`. `query`, when set, is read only if the header is absent (the
value is not percent-decoded). A miss is 401 with `WWW-Authenticate: ApiKey`.

## basic-auth

RFC 7617 `Authorization: Basic`. `users` is one `user:password` per line (or comma-separated
when the value has no newline). `users-file` is the same format in a regular file; on POSIX
the file must not be group- or world-readable (mode `0600` is the usual form). Exactly one of
`users` or `users-file` is required; the file is read when the filter is built. A password may
contain colons. Empty usernames and empty passwords are rejected. At most 1024 users, and a
file is at most 64 KiB.

Each `user:password` is hashed with SHA-256 when the filter is built; the raw password is not
kept. A presented credential is hashed once and compared with every stored hash. `realm`
defaults to `sjws` and is quoted in `WWW-Authenticate: Basic realm="...", charset="UTF-8"`.
A miss, a missing header, a non-Basic scheme, or a malformed token is 401 with that
challenge and `Cache-Control: no-store`. When more than one `Authorization` value is
present, only the first is used. The request path does no I/O. A control-plane proposal must
use `users-file`; inline `users` is refused with 400.
Local config may still use inline `users` (a WARN points at the file form). Viewer reads mask
any retained inline `users` values.

## jwt

Bearer JWT. `alg` is a comma-separated allow-list: `HS256`, `HS384`, `HS512`, `RS256`,
`RS384`, `RS512`, `ES256`, `ES384`, `ES512`. `alg=none` is rejected even if someone puts it in
the list. `hmac-keys` or `hmac-keys-file`, `rsa-keys` and `ec-keys` are comma-separated
`kid:base64` entries (base64url is accepted). A single key may omit the kid. HMAC material is
the raw secret and must be at least as long as the hash; `hmac-keys-file` is the node-local
file form (same secret-file rules as `keys-file`). A control-plane proposal must use
`hmac-keys-file` when HMAC is configured; inline `hmac-keys` is refused with 400. Local config
may still use inline `hmac-keys` (WARN). RSA and EC material is an X.509 SubjectPublicKeyInfo
and may stay inline (public).
RSA keys shorter than 2048 bits are rejected. An HMAC secret that is the encoding of one of the
configured public keys is rejected when the filter is built. Every allowed algorithm needs a
usable key.

`header` defaults to `authorization`. `prefix` defaults to `Bearer `; an empty prefix takes the
whole field. `issuer` and `audience` are exact matches when set (`aud` may be a string or an
array). `exp` is required unless `require-exp` is `false`. `nbf` is checked when present.
`leeway` is whole seconds added to `exp` and subtracted from `nbf` (default 0). A token is
still valid at the exact `exp` instant, and rejected one second later. A present `exp` or `nbf`
that is not a non-negative integer is rejected, including when `require-exp` is false. HMAC
signatures are compared over the whole digest.

The header must not carry `crit`, `jku`, `jwk`, `x5u` or `x5c`. `typ`, when present, must be
`JWT`. A missing `kid` selects the only key of that family; with more than one key the token
must name one. The signature is checked before the claims. A bad token is 401 with
`WWW-Authenticate: Bearer`, not a 500. There is no remote key fetch.

HMAC secrets, API keys and Basic-auth credentials must not sit in the control-plane canonical
document. Use `keys-file` / `hmac-keys-file` / `users-file` so each node reads a local `0600`
file; a proposal that still carries inline `keys`, `hmac-keys` or `users` is refused, and viewer
reads mask those fields. Public JWT keys can travel with the projection. Publishing a new snapshot is the rotation: in-flight
requests keep the previous filter. For clusters that still have inline secrets in older log
entries or snapshots, see the operator note under middleware in
[config-file.md](../user/config-file.md) (file refs, key rotation, snapshot and compaction).

## rate-limit

The same fixed GCRA table as routing `rate_limit` (`routing.GcraTable`). Memory is the stripe
array, not a map of keys. `capacity` is the burst: how many requests a full stripe admits at
once (required, 1..1000000). `refill-per-second` is the steady rate, a positive decimal with up
to six fractional digits and at most 1000000000. `by` is `ip` (default), `header` or `route`.
`header` names the field when `by` is `header`. The route key is the raw request target, hashed
into the table (unlike routing `by: route`, which is one bucket for that route). A missing
address or header shares one extra bucket.

`buckets` is the stripe count, a power of two, default 4096, at most 1048576. `max-keys` is the
old name for that size: it is rounded up to a power of two and does not refuse a new key. Two
keys that land on one stripe share its budget, which is stricter, never looser. Set one of
`buckets` or `max-keys`, not both. A known address is hashed without copying it to a string.
`status` defaults to 429 and must be 400..599. A refusal sets `Retry-After` to the whole seconds
until the stripe would admit another request.

`Quota` is the extension point. The request path calls `tryAcquire` for a header or route key
and `tryAcquireAddress` for an address, and neither may block or do I/O.
`GatewayFilters.install(registry, factory)` uses that factory for
each `rate-limit` entry, so a cluster remainder can refill a local balance on another thread
and answer from it here. The default factory is the striped table.

That factory is also the hook for a **cluster quota share** (rate-limit v2). A later, CP-fed
implementation resizes the local GCRA refill from a share snapshot (`share_rate` /
`share_burst` = configured totals divided across the ready gateway set, remainders assigned
stably so the shares sum to the configured totals). Membership changes are HA's; computing and
publishing the share is the control plane's; this filter must not gossip. The wire type is not
defined here. Default remains the local table (v2 unset, zero cost).

Failure mode, once v2 is enabled: **keep the last share** if a later snapshot is missing or
stale; **fail closed** (the existing 429 + `Retry-After`) if this node has never received a
share, if a CP TTL expires, or if this node is not in the ready set. Do not fall back to the
full local rate — that would admit up to *N* times the global budget. Design §13 in
`docs/design/proxy-routing.md` is the contract. Implementation waits on the CP/HA share API.

## cors

`origins` is `*` or a comma-separated list of exact origins. `credentials` `true` cannot be
combined with `*`. `methods` defaults to `GET,HEAD,OPTIONS` and is matched in upper case.
`headers` lists the names a preflight may request. `expose` is copied to
`Access-Control-Expose-Headers`. `max-age` defaults to 600.

An `OPTIONS` request that includes `Access-Control-Request-Method` is a preflight. When the
origin, method and requested headers are allowed the filter answers 204 and does not call the
rest of the chain. Otherwise it answers 403. Any other request proceeds. Allow-origin,
credentials, expose and `Vary: Origin` are added only when the origin is allowed. `*` does not
add `Vary`.

Put `cors` at a lower order than `api-key` or `jwt` when browsers must preflight without
sending the credential.

## request-size

At least one of `max-bytes` (Content-Length) and `max-target` (raw target length, query
included) is required. The body is not read. Over `max-bytes` is 413. Over `max-target` is 414.
A non-integer or repeated Content-Length is 400. `reject-chunked` `true` answers 411 when
`Transfer-Encoding` contains `chunked`.

## header-rewrite

At least one of `request-set`, `request-remove`, `response-set` and `response-remove` is
required. Each value is a newline-separated list. A set entry is `name: value`. Names and
values are checked when the filter is built; CR, LF and NUL in a value are rejected. Response
rules cannot change `content-length`, `transfer-encoding`, `connection`, `keep-alive`, `date`,
`server` or `alt-svc`. Request rules build one new header map and pass `request.withHeaders`.
Response-only rules do not. Response fields are removed and then set, so a set wins over a
remove of the same name.
