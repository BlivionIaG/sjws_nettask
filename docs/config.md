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

`routing` and `middleware` are file-only. They do not add command-line flags.

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
  access_log: off           # reserved; not written yet

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

The request path is that one volatile read and then the handler that was built
with the snapshot. It does not parse YAML, match routes, or take the reload lock.

Applied on the next request: document root, cache budget, unlistable-directory
policy, the routing table and the middleware list (both stored, neither
dispatched), ACME settings (the renewal task is rescheduled), and a file
certificate (`TlsMaterial.fromPem` into `Http3Server.replaceTls`). HTTP/1
timeouts are applied when HTTP/3 is not running.

Left as they were, and reported as `needsRestart`: `port`, `backlog`, connection
caps, HTTP/3 bind settings, `alt_svc`, `alt_svc_max_age`, and (while HTTP/3 is
running) the idle, header and write timeouts. HTTP/3's QUIC idle timeout and
stream limits are fixed at start.

No config file means reload reports `no config file` and changes nothing.

`installSighup()` points `SIGHUP` at `ReloadHook.run()` and returns false when
the JDK does not expose `sun.misc.Signal`. `main` does not call it: a foreground
process must still die on `SIGHUP`. Daemon mode (PR #5) ignores `SIGHUP` and has
no reload hook; its handler should call `ReloadHook.run()` instead of ignoring
the signal. Whichever handler is installed last owns `SIGHUP`.

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

Storage (created on first issue): `account.key`, `domain.key` (mode 0600 where
the filesystem allows it), `cert.pem`, `account.url`, `directory`. If the
directory URL changes, the account URL is ignored and the account key is kept.

Issuance and renewal run on a daemon thread, never on a request. A certificate
already on disk is published immediately when it is still valid. Renewal is
scheduled for `notAfter - renew_before`. A delay under one minute after a
successful issue is stretched to one hour. Failures back off from 30 seconds,
doubling up to 15 minutes.

The published chain is installed with `Http3Server.replaceTls`. BoringSSL asks
the key manager on every handshake, so new connections use the new certificate
and connections that already shook hands keep the old one.

## Routing

Parsed and stored. Not dispatched: with `enabled: true` the server warns and
still serves the document root. With `enabled: false` (the default) the request
path does not build a router. The records map onto `Route(host, prefix, rewrite,
handler)` and `ProxyHandler.Settings(url, connectTimeout, responseTimeout,
maxBody, proto)`. `proto` is `routing.forwarded_proto` (`auto` is `http` on plain
TCP and `https` on HTTP/3). `idle_read_timeout` (default 30s) and `preserve_host`
(default false) are kept beside that prototype `Settings` record.
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

## Middleware

Reserved and not applied. A non-empty chain warns.

```yaml
middleware:
  chain:
    - name: security-headers
      params: { }
```

`params` values are scalars. Unknown keys are errors.

## Request path

`LiveConfig` holds an `AtomicReference` of `ConfigSnapshot`. The snapshot carries
the static-file handler (and, only when ACME challenges are on the main listener,
the challenge handler in front of it), the route table and the middleware chain.
Building that snapshot is the only time the file is parsed.
