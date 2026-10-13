# Security policy

## Supported versions

sjws_nettask is developed on `master`. Security fixes land on `master` only.

| Version | Supported |
|---|---|
| `master` (built from source, Java 25+) | yes |
| `v0.1.0-alpha` (2017 pre-release) | no: it predates the current code base |

There is no maintained release line yet. When releases resume, the latest release is supported
together with `master`. The build targets Java 25 (`maven.compiler.release` in `pom.xml`), so
earlier JDKs are not supported.

## Reporting a vulnerability

**Do not open a public issue, pull request or discussion for a security problem.**

Report it privately through GitHub's private vulnerability reporting:
**<https://github.com/BlivionIaG/sjws_nettask/security/advisories/new>**
(repository **Security** tab → **Report a vulnerability**). There is no security email address.

Please include:

- the affected component (see [Scope](#scope)) and commit or version;
- configuration and flags needed to reproduce;
- a minimal reproduction (curl/h2load command, test case or script) and the observed impact;
- whether you believe it is already being exploited.

### What to expect

These are goals, not guarantees. This is a small project.

| Step | Goal |
|---|---|
| Acknowledge the report | within 3 business days |
| Initial assessment and severity | within 7 days |
| Fix for Critical / High | as fast as possible, target 30 days |
| Fix for Medium / Low | target 90 days, or the next scheduled release |

You will be kept informed in the advisory thread. If a goal slips, the thread says why.

## Disclosure process

Disclosure is coordinated: **fix first, then advisory.**

1. The report is triaged privately in a GitHub Security Advisory draft.
2. The fix is developed and reviewed, in a private fork of the advisory when the issue is exploitable.
   Public pull requests for undisclosed issues use neutral titles and descriptions.
3. The fix is merged to `master`. A release is cut when releases exist.
4. The advisory is published with affected versions, the fix commit, credit to the reporter (unless
   they ask not to be named) and a CVE when one is warranted.

Please keep the details private until the advisory is published. If no fix is possible within
90 days, we will agree on a disclosure date with you.

Fixed findings from internal reviews are summarised in [docs/security/audit.md](docs/security/audit.md).

## Scope

In scope: the code in this repository, run with its documented configuration.

- **HTTP/1.0 and 1.1, HTTP/2, HTTP/3 (QUIC)**: parsing, request smuggling, resource exhaustion,
  static file serving (path traversal, symlinks, access rules).
- **TLS and ACME**: certificate and key handling, the ACME client and the HTTP-01 challenge listener.
- **Reverse proxy and routing, and the API gateway filters** (API key, JWT, CORS, rate limit,
  request size, header rewrite).
- **Control plane**: the admin API (mTLS, RBAC, audit), signed configuration snapshots, join tokens,
  node bootstrap.
- **Messaging core and the agent swarm layer** (optional, off by default).
- **Cluster / HA**: membership and leader election.
- Build and CI configuration in `.github/`.

Out of scope:

- vulnerabilities in dependencies with no impact on how this project uses them (report them upstream;
  we will still update);
- denial of service that needs more traffic than the configured limits are designed to absorb;
- findings that need a deliberately insecure configuration, local root access, or physical access;
- the legacy NetBeans files (`nbproject/`, `build.xml`) and sample content under `HTML/`.

Some of these components are drafts (see [docs/index.md](docs/index.md)). Reports against them are
welcome. Design-level concerns can also go to their design documents once they are public.

## Secure configuration

What the code enforces today:

- **TLS key passwords are never taken from the command line.** `--key-password` is refused with a
  warning. Use `--key-password-file=PATH` or `WEB_SERVER_TLS_KEY_PASSWORD`.
- **The control-plane admin listener is mTLS only** (TLS 1.3, client certificate required). Identity
  comes from the verified certificate and `Authorization` headers are ignored. The listener refuses to
  bind without its certificate, key and admin-CA bundle. Authorization is deny-by-default RBAC
  (`viewer`, `operator`, `admin`), and every decision is written to the audit log before it is
  returned.
- **Admin certificate rotation and revocation**: the admin listener reloads its certificate, key
  and admin-CA bundle without a restart, either on an explicit reload (`AdminServer.reloadTls()`,
  for example from a SIGHUP handler) or through an optional file watch. A reload that fails keeps
  the previous material and is audited. An optional revocation file is checked at every handshake,
  against every certificate in the client chain. It holds either PEM CRLs, each of which must be
  signed by the admin CA, or a denylist of `sha256:<fingerprint>` and `serial:<hex>` entries. A
  revoked certificate is refused, and each refusal is written to the audit log. A revocation file
  that is configured but missing or invalid fails closed. Reloading invalidates cached TLS
  sessions. Connections that are already open stay open until they close.
- **Join tokens** are one-shot, and this is enforced. The control plane stores only a SHA-256
  hash of each token. A token must be issued before it can be used, and it expires after a TTL
  (default 1 hour, at most 24 hours). It is burned atomically, through the Raft-replicated CP log,
  the first time it is used successfully, and the burn survives restarts and leader changes. A
  second use from any node is refused. The one exception is a retry from the same node with the
  same token within 10 minutes (for example after a lost response): it gets back the registration
  that was already issued, and the token is not accepted again.
- **Configuration snapshots** pushed to data nodes are Ed25519-signed and checked for signature,
  freshness and rollback (version floor) before they are applied.
- **ACME** account and domain keys are written with mode `0600` in a `0700` directory.
- **Fail closed**: invalid configuration, missing TLS material or unknown access rules stop the server
  or refuse the request. They never fall back to open.
- Static files and generated pages (listings, errors, redirects) carry `X-Content-Type-Options:
  nosniff`. Generated pages also carry a script-free `Content-Security-Policy`. Proxied responses keep
  the upstream's headers. `/metrics` is off by default and `localhost` when enabled bare.

Recommendations:

- **Keep cluster and control-plane traffic on a private, trusted network.** The HA heartbeat is plain
  UDP, and the node-to-control-plane channel (an mTLS watch in the design) is not yet authenticated
  in code. Snapshots are signed, but the transport is not encrypted.
- Deliver each join token through a file readable only by the service user, use the shortest
  practical TTL, and never put tokens in arguments, logs or the repository.
- The swarm (`/swarm/v1`) endpoints have no built-in authentication. Enable them only behind the
  gateway's API-key or JWT filters, or on a private listener.
- Issue admin client certificates from a dedicated admin CA. When a key is lost or a person
  leaves, add the certificate to the admin revocation list. Rotate the CA and server certificate by
  replacing the files and reloading.
- Run the server as an unprivileged user that can read the document root and not write to it.

See also [control-plane security](docs/design/control-plane-security.md),
[access rules](docs/user/access-rules.md) and [command line](docs/user/command-line.md).

## Dependency hygiene

- **Dependabot** (`.github/dependabot.yml`): weekly Maven and GitHub Actions updates. Dependabot
  alerts are on.
- **CodeQL** (`.github/workflows/codeql.yml`): `security-extended` queries on every push and pull
  request to `master` and weekly.
- **Bouncy Castle** comes in transitively through acme4j and is pinned in `pom.xml`
  (`bouncycastle.version`) above the version acme4j declares, to pick up security fixes.
- Policy: OSV-Scanner is run during security reviews (last: the 2026-10-13 audit), and Netty,
  SnakeYAML and acme4j are kept on current stable versions. YAML is parsed with `SafeConstructor`
  only.
