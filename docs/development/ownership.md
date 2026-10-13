# Ownership: who owns what

inceptor is the project manager: the only one who merges PRs, decides conflicts between workstreams
and manages the plan. Blivion decides anything marked for review. This roster is kept current as
owners change. Shared rules: [AGENTS.md](../../AGENTS.md).

Paths in the last column are relative to the repo root (`src/sjws_nettask/...` for packages).

| Bot | Owns | Key packages / docs |
|---|---|---|
| inceptor | PM: merges, priorities, conflicts, plan | — |
| sjws Architecture | Overall architecture, design docs, cross-cutting reviews; currently the control-plane watch client + CpStore/ControlPlane | `docs/design/architecture.md`, `docs/design/control-plane.md`; `cp/CpStore.java`, `cp/EndpointWatch.java` |
| sjws Messaging Core | Delivery engine, ledger, partition log (current #1 priority), produce/fetch, durability, messaging benches | `messaging/delivery`, `messaging/ledger`, `messaging/stats`; `docs/messaging/`, `docs/design/partition-log.md`; `bench/LedgerBench.java`, `bench/DeliveryBench.java` |
| sjws Agent Swarm | Swarm runtime, agent graphs, research mode (autoresearch/hill climbing), LLM gateway, Python SDK | `swarm`, `swarm/wire`, `sdk/python`; `docs/design/agent-swarm.md`, `docs/design/agent-graphs.md`; `bench/SwarmBench.java` |
| sjws High Availability | HA and cluster: witness-based two-node HA (#118), failover, replication, discovery EndpointWatch | `ha`, `cluster`, `cp/EndpointWatch.java`; `docs/ha/`, `docs/design/two-node-ha-decision.md` |
| sjws Proxy & Routing | Reverse proxy, routing, UpstreamSource/UpstreamSet, load balancing | `routing`, `routing/discovery`; `docs/design/proxy-routing.md`; `bench/proxy-routing/` |
| sjws Middleware Chain | Filter chain in core, request-level middleware | `core/RequestFilter.java`, `core/RequestFilters.java`, `core/FilterChain.java`, `gateway`; `docs/development/filters.md`, `docs/development/gateway.md` |
| sjws HTTP/1 & HTTP/2 | HTTP/1.x and HTTP/2 transports, ranges, compliance | `http1`, `h2`, `Http1Connection.java`, `core/ByteRanges.java`; `docs/user/http1.md`, `docs/user/http2.md` |
| sjws HTTP Rendering | Directory listings, error pages, rendering | `core/StaticFileHandler.java`, `core/Mime.java` |
| sjws Rights Management | Access rules, file permissions, authz on content | `core/AccessRules.java`, `core/SafeOpen.java`; `docs/user/access-rules.md` |
| sjws Security | Security upgrades, CVE/dependency hygiene, BouncyCastle, SECURITY.md, threat reviews | `pom.xml` (dependencies), `.github/workflows/codeql.yml`; `docs/security/audit.md` (no `SECURITY.md` on master yet) |
| sjws Config & ACME | Configuration (sjws.yaml), TLS certs, ACME | `config`, `tls`, `acme`, `ServerConfig.java`; `docs/user/config-file.md`, `docs/sjws.yaml` |
| sjws Observability | Access logs, /metrics (Prometheus), Grafana examples, tracing, health | `observe`; `docs/operations/observability.md` |
| sjws JVM Footprint | Memory, startup, GC, shared buffer pool and zero-copy | `bench/footprint/`; `docs/benchmarks/footprint-report.md` |
| sjws Admin TUI | Admin TUI and CP console (admin, admin.cp) | `admin`, `admin/cp`; `docs/operations/admin-console.md`, `docs/operations/cp-console.md` |
| sjws Daemon Mode | Daemon/service mode, systemd units | `Daemon.java`, `PidFile.java`, `service`, `contrib/`; `docs/operations/daemon.md`, `docs/operations/control-plane-service.md` |
| sjws CI Release | CI workflows, perf gate, Dockerfile, release builds (no real tags without Blivion) | `.github/workflows/`, `tools/perf-gate/`; `docs/development/perf-gate.md`, `docs/operations/releasing.md` (no Dockerfile on master yet) |
| sjws Integration Tests | Integration/cluster harness, fixtures, e2e scenarios | `tests/sjws_nettask/clusterharness/`, `tests/sjws_nettask/e2e/`; `docs/development/integration-tests.md` |
| sjws Documentation | README, AGENTS.md, docs/ layout, user and operator docs | `README.md`, `AGENTS.md`, `docs/` |

Outside your scope? Ask the owning bot directly. Owners disagree? Ask inceptor.
