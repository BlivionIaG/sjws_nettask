# Security audit (2026-10-13)

Scope: the whole tree at `d592aaf` (HTTP/1.1, HTTP/2, HTTP/3, static files, routing and the reverse
proxy, ACME, config loading, admin/daemon, HA, messaging), plus dependency and repository scanning.
Method: code review, reproductions against the built jar, OSV-Scanner, and the GitHub Dependabot
and code-scanning APIs.

Findings rated High or Critical stay generic here until their fix is merged.

## Findings

| # | Severity | Area | Summary | Status |
|---|---|---|---|---|
| F1 | High | ACME | Resource limit issue in a listener that only runs when ACME is enabled (details withheld until fixed) | Fixed (#54) |
| F2 | Medium | Routing / proxy | Dot-segment check only split segments at `/`. `..;`, `..\`, `..%2f` and `%2e%2e%5c` passed the router and were forwarded unchanged, so an upstream that honours them could resolve a path outside the route prefix or upstream base path | Fixed (#43) |
| F3 | Medium | Routing | Open redirect: a `keep_path` redirect to a relative location turned `/old//evil.example` into `Location: //evil.example` (also `/\evil.example`) | Fixed (#57) |
| F4 | Medium | Dependencies | Bouncy Castle 1.83 (pulled in by acme4j) has 6 advisories: CVE-2025-14813, CVE-2026-0636, CVE-2026-5588, CVE-2026-5598, CVE-2026-8763, CVE-2026-13506. Reachability is limited to the ACME client | Fixed (#48, Bouncy Castle 1.86) |
| F5 | Medium | Proxy | Responses are buffered whole (`max_body`, default 16 MiB) per request. With 2048 HTTP/1 connections, slow clients fetching a large upstream resource can hold gigabytes of heap | Fixed: bodies stream with a bounded read-ahead and a 64 MiB global budget (`-Dsjws.proxy.maxInflightBytes`) |
| F6 | Low | Filters | `RoutePattern` (middleware `route`) matches the raw percent-encoded target, while the file handler decodes it (`/%61dmin` is not `/admin`). No filter shipped today makes access decisions, but a future auth or IP filter scoped by route could be bypassed | Fixed: `RoutePattern` matches the decoded, normalised path |
| F7 | Low | Repository | No code scanning, no Dependabot version updates, secret scanning disabled | CodeQL + Dependabot merged (#49). Secret scanning must be enabled in repo settings |
| F8 | Info | Tests | `Http2ServerTest.concurrentStreamsShareOneConnection` is flaky under load (REFUSED_STREAM from the 100 ms file-slot wait), about 1 failure in 3 on a loaded 8-core box | Open |

## Reviewed with no finding

- **Path traversal and symlinks**: `PathResolver` and `SafeOpen` (`O_NOFOLLOW` and `SecureDirectoryStream`, revalidated cache hits). Covered by `SafeOpenTest` and `VerificationRegressionTest`.
- **HTTP/1 request smuggling**: strict head parser (CL+TE, duplicate CL, obs-fold, bare LF, chunk-size bounds, trailers bounded) and `ChunkedDecoder`.
- **Header injection**: `ResponseHeaders` rejects CR, LF and NUL. Proxied response fields that can't be represented are dropped.
- **HTTP/2**: `MAX_CONCURRENT_STREAMS`, `MAX_HEADER_LIST_SIZE`, RST_STREAM rate limit (rapid reset, CVE-2023-44487), small-CONTINUATION limit (CONTINUATION flood), empty-DATA limit, server and per-connection in-flight caps, idle and write deadlines.
- **HTTP/3 and QPACK**: covered by the earlier security review (`h3/SecurityReviewTest`, `h3/VerificationTest`): stream and connection caps, Retry tokens, handshake caps, rate guard, QPACK limits from Netty.
- **Proxy SSRF**: the upstream is fixed by configuration. The client target must be origin-form, redirects are not followed, and hop-by-hop and forwarding fields are replaced (X-Forwarded-For is extended only from trusted proxies).
- **ACME store**: account and domain keys are written `0600` in a `0700` directory with atomic moves. HTTP-01 tokens are validated against the base64url grammar.
- **Deserialization**: no Java serialization. YAML uses SnakeYAML `SafeConstructor` with duplicate keys refused.
- **Admin, HA, messaging** (as of the audit base `d592aaf`): no network listener. The admin TUI is local, HA probes are read-only, and the messaging ledger is in-process only. The control-plane listeners and `/messaging/v1` came later and are not covered here. `/metrics` is off by default and `localhost` when enabled bare.
- **Response hardening**: `X-Content-Type-Options: nosniff` everywhere and a script-free CSP on generated pages.

## Scanners

- **OSV-Scanner** (`osv-scanner scan source -r .`): 6 advisories, all Bouncy Castle 1.83 (F4). None after #48.
- **Dependabot alerts**: 0 open.
- **Code scanning**: there was no analysis before #49. CodeQL `security-extended` on #49 reports 0 alerts.
- **Secret scanning**: disabled.

## Follow-ups

- Enable secret scanning and push protection in the repository settings.
- Redaction of secrets in snapshots and logs. (mTLS between nodes, signed and versioned config snapshots and single-use join tokens have landed, see [SECURITY.md](../../SECURITY.md).)
