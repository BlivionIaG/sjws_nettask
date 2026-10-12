# sjws_nettask documentation

Back to the [README](../README.md) (quick start).

## Using the server
- [Installation and build](user/installation.md)
- [Command line and environment](user/command-line.md): option tables (canonical; checked against `--help` by `DocsConsistencyTest`), precedence, exit codes
- [Configuration value formats](user/configuration.md)
- [YAML config file](user/config-file.md)
- [HTTP/1.x](user/http1.md) · [HTTP/2](user/http2.md) · [HTTP/3](user/http3.md)
- [Access rules](user/access-rules.md)
- [Caching, validators and ranges](user/caching-and-ranges.md)
- [Platform notes](user/platform-notes.md)

## Operations
- [Observability](operations/observability.md): access log, `/healthz`, `/metrics`, metrics API
- [Running in the background (daemon)](operations/daemon.md)
- [Admin console (TUI)](operations/admin-console.md)
- [Releasing](operations/releasing.md)

## Design
- [Architecture](design/architecture.md)
- [Implementation overview and measured performance](design/implementation-and-performance.md)
- [Request routing and reverse proxy (proposal)](design/proxy-routing.md)
- [Control plane: distributable access policy, operator RBAC and audit](design/control-plane-security.md)
- [Messaging core (draft)](messaging/design.md)
- [High availability (draft)](ha/design.md)
- [Control plane / API gateway mode (draft)](design/control-plane.md)

## Security
- [Security audit (2026-10-13)](security/audit.md)

## Development
- [Contributing](../CONTRIBUTING.md)
- [Test suite catalog](development/testing.md)
- [Integration and end-to-end tests](development/integration-tests.md)
- [Request filters](development/filters.md)
- [Perf-regression gate (CI)](development/perf-gate.md)

## Benchmarks
- [Footprint report](benchmarks/footprint-report.md); harnesses: [bench/](../bench/README.md), [bench/footprint/](../bench/footprint/README.md)
