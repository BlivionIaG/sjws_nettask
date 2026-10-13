# Configuration

The server reads one YAML file. YAML, rather than another syntax, because the proxy
workstream's routing schema is YAML and has to paste in unchanged. Parsing uses
SnakeYAML's `SafeConstructor` only: no arbitrary types, duplicate keys are errors,
and the document is capped at 1 MB.

```
java -jar sjws_nettask.jar --config=sjws.yaml
```

`WEB_SERVER_CONFIG` names the same file and wins over `--config`.

## Precedence

Defaults, then the file, then arguments, then environment variables.

The file is strict: an unknown key or a bad value is an error and the process exits
64. The existing server flags stay lenient (a bad number warns and falls back).
There is no `--key-password` and no `key_password` key; a password lives in
`tls.key_password_file` or `WEB_SERVER_TLS_KEY_PASSWORD`.

Paths in the file are resolved against the file's directory. Paths on the command
line and in the environment stay relative to the working directory.

`--help` wins before a missing file is an error. A missing document root is exit
code 2, including when the root comes from the file.

`routing`, `middleware`, `access` and `node` are file-only. They do not add command-line flags.

## Node

Omitted, the process is `standalone`. The request path does not read the mode.

```yaml
schema: 1
version: 0          # control-plane bundle version; 0 means unset
node:
  mode: standalone  # standalone (default), control, or data
  labels: {}        # up to 64 keys; each key and value is 1..63 of [A-Za-z0-9_.:-]
  state_dir: /var/lib/sjws_nettask   # optional; local, not part of the canonical hash
```

`schema` must be 1 when it is present. A reload or a remote apply rejects a non-zero
`version` that is lower than the one already running. `node.mode` is kept until
restart; `labels` apply on the next successful apply.

A data-plane process whose config file is missing boots from
`<stateDir>/config/last-good.json` when that file exists, the hash in the envelope
matches the embedded document, and `node.mode` is `data`. `stateDir` is
`node.state_dir`, or `--state-dir` / `WEB_SERVER_STATE_DIR` (those win over the
file), and otherwise the daemon's data directory: the working directory, where the
default pid and log files are written. The `config` directory is mode `0700` and
the file is mode `0600`. The bytes are a JSON envelope around the canonical
document, so they do not contain a key password. The temp file and its directory
are fsynced before the atomic rename, and the write runs after the reload lock is
released. Standalone and control do not write this file and do not boot from it.
A file left by an older build at `<file>.last-good` is still read once when the
JSON file is absent. A failed apply leaves both the running config and this file
as they were.

In a duration, a bare integer is seconds and `1m` is one minute (`ms`, `s`, `m`,
`h`, `d`). In a size, a bare integer is bytes and `1m` is one mebibyte (`k`, `m`,
`g`, 1024-based). Server timeouts are whole seconds, 1..86400, matching the flags.

## Server

```yaml
server:
  port: 8080
  root: ./HTML
  max_connections: 2048
  max_connections_per_ip: 256
  backlog: 1024
  idle_timeout: 30
  header_timeout: 10
  write_timeout: 30
  cache_bytes: 64m          # up to 16g; 0 disables the cache
  allow_unlistable_dirs: false
  access_log: off           # opened at startup; a reload does not reopen the writer

http3:
  enabled: false
  port: same                # or a UDP port; 0 = ephemeral
  host: 0.0.0.0
  max_connections: 1024
  max_connections_per_ip: 16
  min_rate: 2048
  alt_svc: false
  alt_svc_max_age: 86400

tls:
  cert: cert.pem
  key: key.pem
  key_password_file: key.pass
```

`tls.cert` and `tls.key` are set together. `tls.cert` together with `acme.enabled`
is an error: ACME owns the certificate.

## Reload

`ReloadHook.register(Runnable)` returns a handle that removes that hook.
`ReloadHook.run()` runs every hook. A hook that throws is logged (`ERROR: reload
hook failed:`) and the others still run.

The server registers itself at the end of `start` and removes the hook at the
start of `close`. The hook re-reads the file, reapplies the original arguments
and the environment captured at startup, and on success compiles a new immutable
snapshot and publishes it with one `AtomicReference` write. A validation error,
a missing root, or an unreadable certificate keeps the previous snapshot.
`SjwsServer.applyRemote` checks the injected signature verifier, then validates and
publishes a pushed document through that same swap. A data or control process
that was not given a verifier rejects the push. Standalone uses a no-op verifier.
A bad signature, a canonical hash that is not the SHA-256 of the canonical
document, or a lower non-zero `version` is rejected before the swap. An incoming
canonical hash equal to the running snapshot's is a no-op: the file cache and
upstream pools are left in place. It does not re-read the file and it does not
replace the file: the user's YAML stays put. A data node writes last-good only
after the swap, and only after the reload lock is released. HTTP/1 and HTTP/3
can observe the new snapshot a moment apart: the publish updates the live
reference, then the HTTP/3 handler and certificate.

The request path is that one volatile read and then the handler that was built
with the snapshot. It does not parse YAML, match routes, or take the reload lock.

Applied on the next request: document root, cache budget, unlistable-directory
policy, the routing table, the middleware list and the access settings (stored,
not dispatched), ACME settings (the renewal task is rescheduled), and a file
certificate (`TlsMaterial.fromPem` into `Http3Server.replaceTls`). HTTP/1
timeouts are applied when HTTP/3 is not running. The access-log writer, metrics
exposure and `/healthz` probe stay as they were opened at startup.

Left as they were, and reported as `needsRestart`: `port`, `backlog`, connection
caps, HTTP/3 bind settings, `alt_svc`, `alt_svc_max_age`, and (while HTTP/3 is
running) the idle, header and write timeouts. HTTP/3's QUIC idle timeout and
stream limits are fixed at start.

No config file means reload reports `no config file` and changes nothing.

`installSighup()` points `SIGHUP` at `ReloadHook.run()` and returns false when
the JDK does not expose `sun.misc.Signal`, and when this process already ignores
`SIGHUP`. An ignored `SIGHUP` stays ignored: the VM does not install a Java
handler over it. `main` does not call it: a foreground process must still die on
`SIGHUP`. The daemon child ignores `SIGHUP` and has no reload hook; its handler
should call `ReloadHook.run()` instead of ignoring the signal.

## ACME

HTTP-01 (RFC 8555) via acme4j. The default directory is Let's Encrypt **staging**
(`https://acme-staging-v02.api.letsencrypt.org/directory`). `production` is the
production directory. An `https://`, `http://` or `acme://` URL is used as given.

```yaml
acme:
  enabled: false
  directory: staging
  email: ops@example.com
  domains: [example.com]   # no wildcards; HTTP-01 cannot prove one
  storage: ./acme
  http_port: 0              # 0, or the server port: challenges on the main listener
  accept_terms: false       # must be true when enabled
  renew_before: 30d         # at most 89 days
```

When enabled, email, at least one domain and `accept_terms: true` are required.
A standalone process issues as before. A control node issues only after
`ControlLeadership.leaderReady(term, appliedIndex)`, which fires once that term's
election NOOP is applied, and stops issuing when the term changes.
`isLeader()` alone does not start an order. A data node does not start ACME;
its certificate arrives with the pushed config. Logs drop a message that contains
a private key or a certificate PEM.
The same checks on shape (directory, domain spelling, durations) run when it is
off, so a typo is still an error. CLI and environment overrides use `--acme`,
`--no-acme`, `--acme-directory`, `--acme-email`, `--acme-domain` (repeatable),
`--acme-storage`, `--acme-http-port`, `--acme-accept-terms`, `--acme-renew-before`
and the `WEB_SERVER_ACME*` variables in `--help`. A bad ACME flag is an error,
not a warning.

`http_port` other than 0 and other than the server port opens a small extra
HTTP/1.1 listener that only serves challenges. `/.well-known/acme-challenge/` is
matched before the static handler whenever ACME is on the main listener. An
unknown token is 404 and is not served as a file.

Storage (created on first issue): `account.key`, `domain.key` (PKCS#8 `PRIVATE KEY`,
mode 0600 where the filesystem allows it), `cert.pem`, `account.url`, `directory`.
If the directory URL changes, the account URL is ignored and the account key is kept.
An integration test issues a certificate against Pebble on linux/amd64.

Issuance and renewal run on a daemon thread, never on a request. A certificate
already on disk is published immediately when it is still valid. Renewal is
scheduled for `notAfter - renew_before`. A delay under one minute after a
successful issue is stretched to one hour. Failures back off from 30 seconds,
doubling up to 15 minutes.

The published chain is installed with `Http3Server.replaceTls`. BoringSSL asks
the key manager on every handshake, so new connections use the new certificate
and connections that already shook hands keep the old one.

## Routing

With `enabled: true` each snapshot (startup and every reload) compiles the section
into a `routing.Router` in front of the static handler (`config.RoutingCompiler`):
`proxy` routes share one `ProxyHandler` (one connection pool) per upstream,
`redirect` routes answer directly, `static` and unmatched requests go to the
document root, and a path with a `..` segment is 400. ACME challenges are
answered before any route. With `enabled: false` (the default) no router is built
and the request path is unchanged. The records map onto `Route(host, prefix, rewrite,
handler)` and `ProxyHandler.Settings(url, connectTimeout, responseTimeout,
idleReadTimeout, maxBody, proto, trustedProxies, preserveHost, maxIdle)`. The proxy is a Netty
HTTP/1.1 client with up to 256 idle keep-alive connections per upstream, and it streams
response bodies with backpressure. HTTP/1.1 request bodies with a `Content-Length` are
streamed upstream (over `max_body` is 413). A chunked request body, or a body on HTTP/2 or
HTTP/3, is 501 for now; see `docs/design/proxy-routing.md` §12. `proto` is `routing.forwarded_proto` (`auto` is `http` on plain
TCP and `https` on HTTP/3). `idle_read_timeout` (default 30s) bounds the gap between upstream body
bytes. Proxied body bytes held in memory are bounded per request (a 256 KiB read-ahead window)
and in total (`-Dsjws.proxy.maxInflightBytes`, default 64 MiB); slow clients wait instead of
growing the heap (security finding F5). `preserve_host: true` forwards the client's `Host` instead of the upstream's. A reload or stop
closes the replaced pools.
`trusted_proxies` is a list of IP or CIDR literals (no DNS).

```yaml
routing:
  enabled: false
  upstreams:
    api: { url: "http://127.0.0.1:9000", connect_timeout: 5s, response_timeout: 30s, max_body: 16m }
  routes:   # ordered, first match wins
    - host: "example.com"  # optional; exact or "*.suffix"
      prefix: "/api"       # required, segment-boundary match
      rewrite: "/"         # optional
      proxy: api           # exactly one of proxy | redirect | static
    - prefix: "/old"
      redirect: { status: 301, location: "https://example.com/new/", keep_path: true }
    - prefix: "/"
      static: true
  forwarded_proto: auto   # http|https|auto
```

`prefix` matches on a segment boundary (`/api` matches `/api`, `/api/…` and
`/api?…`, not `/apix`) against the raw percent-encoded target. `host` is
case-insensitive; a port in the request's Host header is ignored; `*.example.com`
matches a subdomain and not the apex. A route that an earlier route completely
covers is a warning. The sample above does not shadow itself.

### Per-route error format and listings

These settings are part of the route records (`RouteSpec`), so they travel with the
rest of the routing section to every node that loads it. Defaults keep today's behaviour.

```yaml
  routes:
    - prefix: "/api"
      proxy: api
      errors: problem                               # html (default) | problem
      problem_type: "https://errors.example.com/"   # optional, absolute URI; needs errors: problem
    - prefix: "/files"
      static: true
      listing: false                                # static routes only; default true
```

`errors: problem` renders the error pages this server generates for the route as
RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail` and
`instance`, for 400, 401, 403, 404, 405, 408, 413, 429, 500, 501, 502, 503 and
504. Other statuses keep the HTML page. A response relayed from an upstream (for
example the upstream's own 503) is never rewritten. `type` is `about:blank`, or
`problem_type` followed by the status code (`https://errors.example.com/503`).
`instance` is the request path as the client sent it (before `rewrite`, without
the query). The body carries `Cache-Control: no-store`, and semantic fields of the
page such as `Allow`, `Content-Range` or `Retry-After` are kept. Routes without
`errors: problem` get no wrapper and run exactly as before.

`listing: false` answers a directory without `index.html` with 404, as if it did
not exist. The route still shares the document root, access policy and file cache.

## Middleware

A list, stored and not applied. An empty list (or a missing key) is no chain and
does not warn. A non-empty list warns. The same `name` may appear more than once.
File order is kept; a lower `order` is further out, and ties stay in that order
when a filter chain sorts them. Each entry is
`sjws_nettask.core.MiddlewareSpec.of(name, order, route, options)`, and `route`
is checked with `RoutePattern.parse`. The snapshot holds that list. Startup does
not call `RequestFilters.addAll`: no factories are bound, and an unknown name
would fail the build. A caller that has bound factories can still pass
`RequestFilters` to `SjwsServer.start`.

```yaml
middleware:
  - name: access-log          # required
    order: 0                  # optional, default 0; lower is further out
    options:                  # optional; numbers and booleans become strings
      format: combined
  - name: reverse-proxy
    order: 100
    route: /api/*             # optional; omitted, blank or "*" = every request
    options:
      upstream: http://127.0.0.1:9000
      preserve-host: "true"
```

`route` is `*`, `/exact`, or `/prefix/*` (and `/*` for every path that starts
with `/`). Any other `*` is an error.

## Access

Enforced by the file handler over HTTP/1.1, HTTP/2 and HTTP/3, and re-applied
on every reload (add a rule and reload: it is enforced on the next request; remove
it and reload: it is lifted). A malformed rule or an unknown `require_owner` is an
error, and a reload that fails keeps the previous rules. Each field has a flag and
an environment variable:

| YAML | Flag | Environment |
|---|---|---|
| `deny` | `--deny=PAT[,PAT...]` | `WEB_SERVER_DENY` |
| `rules` | `--access-rule=ACT:PAT` | `WEB_SERVER_ACCESS_RULES` |
| `allow_dotfiles` | `--allow-dotfiles` | `WEB_SERVER_ALLOW_DOTFILES` |
| `require_owner` | `--require-owner=USER` | `WEB_SERVER_REQUIRE_OWNER` |
| `require_world_readable` | `--require-world-readable` | `WEB_SERVER_REQUIRE_WORLD_READABLE` |

```yaml
access:
  deny: ["*.bak", "*~", "/private"]   # or one comma-separated string
  rules:                               # allow:, deny: or hide:; first match wins there
    - hide:/drafts
    - allow:/.well-known/acme-challenge
  allow_dotfiles: false
  require_owner: www-data              # name or numeric uid; omit to leave it off
  require_world_readable: false
```

### Precedence with the command line

The YAML section and the flags / environment are **merged**, never one replacing
the other, so nothing configured in either place is silently dropped:

| Field | Effective value |
|---|---|
| `deny` | union: flag/environment patterns, then YAML patterns (all answer 403, before any rule) |
| `rules` | flag/environment rules first, then YAML rules; first match wins, so a flag rule overrides a YAML rule for the same path |
| `allow_dotfiles` | on if either source turns it on (each is an explicit opt-in) |
| `require_world_readable` | on if either source turns it on |
| `require_owner` | the flag/environment value when set, otherwise the YAML value |

With no `access:` section (or a default one) the flag policy is used as is, with
no extra cost per request.

`/.well-known/acme-challenge/` is answered by the ACME handler before the file
handler, including its dotfile check, whenever ACME is on the main listener. An
unknown token is 404 and is not a file. When ACME is enabled the stored rules
also gain `allow:/.well-known/acme-challenge` if it is not already there: a
wildcard allow does not match a dot component, and that literal is the form the
access-rule list needs. A dedicated HTTP-01 port never consults the file handler.

## Request path

`LiveConfig` holds an `AtomicReference` of `ConfigSnapshot`. The snapshot carries
the static-file handler with the merged access rules (and, only when ACME challenges are on the main listener,
the challenge handler in front of it), the route table, the middleware list and
the access settings. Building that snapshot is the only time the file is parsed.

## ConfigStore rollout

The bundle is this YAML file. `canonicalHash()` is the lowercase SHA-256 of the
canonical YAML of the effective document (fixed key order, sorted maps, no key
password, and not the hash itself). Reordering keys in the file leaves it
unchanged. A signature covers that hash, the bundle `version`, and `schema`:
the bytes are `schema`, a newline, `version`, a newline, and the hex hash.
Distribution sends the full signed document. `ConfigDiff` reports which keys
were added, removed, or changed in each section. It is not a patch and it cannot
rebuild a document. `version` (`bundleVersion()`) is the control-plane bundle
version. Each apply that actually changes the snapshot bumps a generation counter;
that counter is how many times this process has swapped, not the bundle version.
A repeated push of the same canonical hash does not bump it. Bind-time fields
that are clamped back to the running value are reflected in the canonical hash of
the snapshot that was published, so that hash can differ from the hash of the
bundle that was pushed. The canonical text is rendered once for that hash and
reused for the last-good envelope.

Applied live, on the next request, with no drain: document root, cache budget,
unlistable directories, HTTP/1 timeouts when HTTP/3 is not running, the stored
routing table, middleware list and access settings, and ACME's renewal schedule.
A file certificate is loaded and passed to `Http3Server.replaceTls`.

Needs a restart (the previous value is kept and named in the reload result):
`server.port`, `server.backlog`, both connection caps, whether HTTP/3 is enabled,
its port, host, connection caps, minimum rate, Alt-Svc and its max-age, and the
idle, header and write timeouts while HTTP/3 is running. QUIC's idle timeout and
stream limits are fixed when the UDP socket is opened. Removing `tls.cert` leaves
the certificate that is already installed until restart. The access-log writer,
the metrics exposure and the `/healthz` probe are opened at startup and are not
replaced on reload.

TLS. HTTP/1.1 on this server is plain TCP; there is no certificate on that
listener, so ACME renewal does not drain HTTP/1 connections. HTTP/3 uses a key
manager that BoringSSL asks on every handshake. `replaceTls` publishes the new
chain for the next handshake. Connections that already finished a handshake keep
the previous certificate until they close. Renewal does not restart the process
and does not drain the UDP listener.

Secrets. The file is not scanned for `${env:NAME}` or `${file:path}` placeholders.
The one secret is the TLS key password. It is never a YAML value and never a
command-line argument. `tls.key_password_file` is read when HTTP/3 stays enabled
and neither `WEB_SERVER_TLS_KEY_PASSWORD` nor `WEB_SERVER_TLS_KEY_PASSWORD_FILE`
nor `--key-password-file` was set. The environment variable wins over the file.
