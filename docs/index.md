# sjws_nettask documentation

**SJWS** (Swarm Java Web Services): message-oriented middleware with HA, an optional agent swarm, and a
web server. Back to the [README](../README.md) (quick start and shipped/planned Status table).

## Using the server
- [Installation and build](user/installation.md)
- [Command line and environment](user/command-line.md): option tables (canonical; checked against `--help` by `DocsConsistencyTest`), precedence, exit codes
- [Configuration value formats](user/configuration.md)
- [YAML config file](user/config-file.md)
- [HTTP/1.x](user/http1.md) · [HTTP/2](user/http2.md) · [HTTP/3](user/http3.md)
- [Access rules](user/access-rules.md)
- [API gateway](user/api-gateway.md): routes, auth and rate limits
- [Caching, validators and ranges](user/caching-and-ranges.md)
- [Platform notes](user/platform-notes.md)
- [Cluster mode (HA)](user/cluster-mode.md)

## Operations
- [JVM flags (profiles)](operations/jvm-flags.md): G1 default; opt-in ZGC pinned / Serial; container MaxRAMPercentage
- [Observability](operations/observability.md): access log, `/healthz`, `/metrics`, metrics API, control heartbeat report
- [Running in the background (daemon)](operations/daemon.md)
- [Docker image](deployment/docker.md): multi-stage build, non-root runtime, health check
- [Control-plane and data-node service](operations/control-plane-service.md): systemd units, join bootstrap, status, drain
- [Control-plane operations runbook](operations/control-plane.md): bootstrap voters, join data nodes, backup, upgrades, DR
- [Admin console (TUI)](operations/admin-console.md)
- [Control-plane console](operations/cp-console.md)
- [Releasing](operations/releasing.md)

## Design
- [Architecture](design/architecture.md)
- [Implementation overview and measured performance](design/implementation-and-performance.md)
- [Request routing and reverse proxy (design and results)](design/proxy-routing.md)
- [Control plane: distributable access policy, operator RBAC and audit](design/control-plane-security.md)
- [Messaging core (draft)](messaging/design.md)
- [Messaging metrics (proposal)](design/messaging-metrics.md)
- [Messaging config and broker apply (draft)](messaging/config.md)
- [Durable partition log](design/partition-log.md) · [plan](design/durable-log-plan.md)
- [High availability, as implemented](design/high-availability.md)
- [High availability (draft)](ha/design.md) · [two-node decision](design/two-node-ha-decision.md) · [upstream discovery](ha/discovery.md)
- [Raft delivery executor](design/raft-delivery-executor.md)
- [Swarm peer forwarding (#236), as implemented](design/swarm-peer-forwarding.md)
- [Control plane / API gateway mode (draft)](design/control-plane.md) · [process hosting](design/control-plane-hosting.md)
- [Agent swarm layer (optional, draft)](design/agent-swarm.md)
- [Agent graphs (swarm addendum, draft)](design/agent-graphs.md)
- [Swarm research mode (draft)](design/swarm-research-mode.md)
- [Shared buffer pool and zero-copy (draft)](design/buffer-pool.md)

## Security
- [Security policy and vulnerability reporting](../SECURITY.md)
- [Security audit (2026-10-13)](security/audit.md)

## Development
- [Contributing](../CONTRIBUTING.md) · [AGENTS.md](../AGENTS.md) (guide for coding agents)
- [Ownership: who owns what](development/ownership.md)
- [Test suite catalog](development/testing.md)
- [Integration and end-to-end tests](development/integration-tests.md)
- [Request filters](development/filters.md)
- [API gateway filters](development/gateway.md)
- [Perf-regression gate (CI)](development/perf-gate.md)

## Benchmarks
- [Footprint report](benchmarks/footprint-report.md); harnesses: [bench/](../bench/README.md), [bench/footprint/](../bench/footprint/README.md)
