# Ownership: who owns what

inceptor is the project manager: the only one who merges PRs, decides conflicts between workstreams
and manages the plan. Blivion decides anything marked for review. This roster is kept current as
owners change. Shared rules: [AGENTS.md](../../AGENTS.md).

The sjws bots are cut to **four** workstreams (plus inceptor). Retired names (Agent Swarm, High
Availability, Proxy & Routing, Middleware Chain, HTTP Rendering, Rights Management, Config & ACME,
Observability, JVM Footprint, Admin TUI, Daemon Mode, CI Release, Integration Tests, Documentation)
map into the rows below; see the issue comment on
[#380](https://github.com/BlivionIaG/sjws_nettask/issues/380#issuecomment-6549431235).

Paths in the last column are relative to the repo root (`src/sjws_nettask/...` for packages).

| Bot | Owns | Key packages / docs |
|---|---|---|
| inceptor | PM: merges, priorities, conflicts, plan | — |
| sjws Messaging Core | Messaging core, high availability / CP, agent swarm | `messaging/`, `messaging/delivery`, `messaging/ledger`, `messaging/stats`, `msgrepl/`, `ha/`, `cluster/`, `cp/` (`CpStore`, `EndpointWatch`), `swarm/`, `swarm/wire`, `sdk/python`; `docs/messaging/`, `docs/ha/`, `docs/design/partition-log.md`, `docs/design/control-plane.md`, `docs/design/two-node-ha-decision.md`, `docs/design/agent-swarm.md`, `docs/design/agent-graphs.md`, [`docs/design/swarm-research-mode.md`](../design/swarm-research-mode.md); `bench/LedgerBench.java`, `bench/DeliveryBench.java`, `bench/SwarmBench.java` |
| sjws Architecture | Architecture, CI/release, documentation, daemon mode, integration tests, observability, admin TUI, JVM footprint | `docs/design/architecture.md` (plus cross-cutting design reviews); `.github/workflows/`, `tools/perf-gate/`, [`Dockerfile`](../../Dockerfile), `docs/development/perf-gate.md`, `docs/operations/releasing.md`; `README.md`, `AGENTS.md`, `docs/`; `Daemon.java`, `PidFile.java`, `service/`, `contrib/`, `docs/operations/daemon.md`, `docs/operations/control-plane-service.md`; `tests/sjws_nettask/clusterharness/`, `tests/sjws_nettask/e2e/`, `docs/development/integration-tests.md`; `observe/`, `docs/operations/observability.md`; `admin/`, `admin/cp/`, `docs/operations/admin-console.md`, `docs/operations/cp-console.md`; `core/buf/`, `bench/footprint/`, `docs/benchmarks/footprint-report.md`, [`docs/design/buffer-pool.md`](../design/buffer-pool.md) |
| sjws Security | Security, rights management, config & ACME | `pom.xml` (dependencies), `.github/workflows/codeql.yml`, [`SECURITY.md`](../../SECURITY.md), `docs/security/audit.md`; `core/AccessRules.java`, `core/SafeOpen.java`, `docs/user/access-rules.md`; `config/`, `tls/`, `acme/`, `ServerConfig.java`, `docs/user/config-file.md`, `docs/sjws.yaml` |
| sjws HTTP/1 & HTTP/2 | HTTP/1.x, HTTP/2, HTTP/3, proxy & routing, HTTP rendering, middleware chain | `http1/`, `h2/`, `h3/`, `Http1Connection.java`, `core/ByteRanges.java`, `docs/user/http1.md`, `docs/user/http2.md`, `docs/user/http3.md`; `routing/`, `routing/discovery/`, `docs/design/proxy-routing.md`, `bench/proxy-routing/`; `core/StaticFileHandler.java`, `core/Mime.java`; `core/RequestFilter.java`, `core/RequestFilters.java`, `core/FilterChain.java`, `gateway/`, `docs/development/filters.md`, `docs/development/gateway.md` |

Outside your scope? Ask the owning bot directly. Owners disagree? Ask inceptor.
