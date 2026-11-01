# AGENTS.md: guide for coding agents

Read this before working on the repo. Details live in [docs/](docs/index.md); human contributors see
[CONTRIBUTING.md](CONTRIBUTING.md).

## Goal

**SJWS** (Swarm Java Web Services; repo `sjws_nettask`) is a **reliable, fast, low-latency
message-oriented middleware that is also a web server**: HTTP/1.x, HTTP/2, HTTP/3, reverse
proxy/gateway, control plane, cluster HA (per-group Raft), messaging (partitions, ledger,
produce/fetch) and an optional agent swarm. Full cluster messaging replication (`ReplicatedLedger`)
and the client messaging wire protocol beyond produce/fetch are still in design
([docs/messaging/design.md](docs/messaging/design.md)); see the README Status table.

## Module map (`src/sjws_nettask/`, NetBeans layout; tests in `tests/sjws_nettask/`)

| Area | Package / path | Docs |
|---|---|---|
| Entry, config, lifecycle | `Sjws_nettask`, `ServerConfig`, `SjwsServer`, `ReloadHook`; `config` (YAML) | [command line](docs/user/command-line.md), [config file](docs/user/config-file.md) |
| Core (protocol-neutral) | `core`: `Handler`, `Request`/`Response`, `StaticFileHandler`, `SafeOpen`, `FileCache`, `AccessRules` | [architecture](docs/design/architecture.md) |
| Middleware chain | `core.RequestFilter`, `RequestFilters`, `FilterChain`, `MiddlewareSpec` | [filters](docs/development/filters.md) |
| HTTP/1.x | `ClientManager`, `Http1Connection`, `ConnectionReaper`; `http1` | [http1](docs/user/http1.md) |
| HTTP/2 | `h2` | [http2](docs/user/http2.md) |
| HTTP/3 (QUIC) + TLS | `h3`, `tls`, `acme` | [http3](docs/user/http3.md) |
| Proxy / routing | `routing` (`Router`, `ProxyHandler`, `RedirectHandler`), `routing.discovery` | [proxy-routing](docs/design/proxy-routing.md) |
| API gateway filters | `gateway` (JWT, API key, CORS, rate limit, quotas, size) | [gateway](docs/development/gateway.md) |
| Messaging | `messaging.delivery`, `messaging.ledger`, `messaging.stats` | [messaging](docs/messaging/design.md), [config](docs/messaging/config.md) |
| Cluster / HA | `cluster` (Raft groups, log storage), `ha` | [HA](docs/ha/design.md), [discovery](docs/ha/discovery.md) |
| Control plane | `controlplane` (RBAC, policy, audit), `cp` (admin server, store, snapshots, join tokens), `service` (node roles, bootstrap) | [control plane](docs/design/control-plane.md), [security](docs/design/control-plane-security.md), [service](docs/operations/control-plane-service.md) |
| Agent swarm (optional module) | `swarm`, `swarm.wire` | [agent-swarm](docs/design/agent-swarm.md) |
| Observability | `observe` | [observability](docs/operations/observability.md) |
| Admin TUI | `admin` (server console), `admin.cp` (control-plane console) | [admin console](docs/operations/admin-console.md), [cp console](docs/operations/cp-console.md) |
| Daemon mode | `Daemon`, `PidFile`; `contrib/` (systemd units, `sjws` wrapper) | [daemon](docs/operations/daemon.md) |
| Python SDK | `sdk/python` | |
| Bench / perf tools | `bench/`, `tools/` | [perf gate](docs/development/perf-gate.md) |

`SourceGuardTest` enforces the boundaries: no `synchronized`; no Netty in `core`, `http1` or
`cluster`; `core` imports no transport and no sockets; `h3` doesn't import `http1`; `cluster` doesn't
import `ha`/`http1`; nothing outside `swarm` depends on it (except `OptionalModules`); `swarm.wire`
imports only `core`.

## Build and test

Java 25 (`maven.compiler.release` 25) and Maven.

    mvn -B verify                    # all tests, jar tests, JaCoCo gates
    mvn test -DexcludedGroups=h3     # without QUIC natives

**JaCoCo gates** are per package in `pom.xml` (`DocsConsistencyTest` requires one for every package).
Examples: root `sjws_nettask` line 0.96 / branch 0.96, `http1` 0.98 / 0.97, `core` 0.94 / 0.89,
`swarm` 0.95 / 0.90, `config` 0.81 / 0.73; `h3`/`tls` use the `h3.gate.*`/`tls.gate.*` properties
(platform profiles). Check `pom.xml` for the current numbers; don't lower a gate to pass.

**CI** (`.github/workflows/`):
- `ci.yml` (CI), job `verify`: `mvn -B verify`, Python SDK tests, `e2e` and `cluster` test groups,
  gateway bench; job `mutation` (PIT): weekly and on manual dispatch only.
- `perf-gate.yml` (Perf gate), job `perf`: wrk A/B of PR base vs head on PRs touching `src/**`,
  `pom.xml` or the gate; label `skip-perf` skips it.
- `codeql.yml` (CodeQL), job `analyze`: java-kotlin, on PRs to master, pushes and weekly.
- `cluster-tests.yml` (Cluster tests), job `cluster`: nightly, manual, or the `cluster-tests` PR label.
- `release.yml`: on `v*` tags ([releasing](docs/operations/releasing.md)).
- No `continue-on-error` on jobs that must fail the workflow (`ci.yml` enforces this; see #356).
- Cursor cloud agents often cannot `gh workflow run` (403 without `actions: write`); a human dispatches.

Docs checks: `DocsConsistencyTest` (every `--flag`/`WEB_SERVER_*` in `--help` appears in the option
tables listed in `DocsConsistencyTest.OPTION_DOCS`, defaults match). Keep relative links valid.

## Efficiency rule

The server is latency-first. A PR that touches the request path must include **wrk/wrk2 before/after
numbers** (same host, same flags) in its body; the perf gate is a backstop, not a substitute. A
feature that is disabled must cost **zero** on the request path (no filter installed, no clock read,
no counter: see how observability is wired). Docs, test, CI and dependency-only PRs are exempt.

## Commits and PRs

- Allowed commit authors/committers: `BlivionIaG <15323295+BlivionIaG@users.noreply.github.com>`,
  Cursor Agent, Dependabot. Use the global git identity; no repo-local overrides. Check
  `git log --format='%an <%ae> | %cn <%ce>'` before pushing.
- Never force-push; never push to `master`. Branch off `origin/master`, open a PR against it.
- Small, focused PRs; avoid file moves/renames that make parallel branches conflict.
- To catch up, **merge `master` into your branch** instead of rebasing.
- **inceptor** (the project-manager agent) merges PRs and decides conflicts between workstreams.
  Area owners: [docs/development/ownership.md](docs/development/ownership.md).
- inceptor merges once CI is green. `Do not merge` in a PR body holds the PR out of the merge sweep.
- A blocking review is a comment containing `CHANGES REQUESTED`; a later `APPROVED` comment from the
  same reviewer clears it.

## Shared box etiquette

The box (8 cores, 15 GB RAM) is shared by every bot.

- Run every benchmark (wrk, wrk2, JMH, `LogBench`, the swarm benches) as `flock /tmp/sjws-bench.lock <cmd>`.
- Run local builds as `nice -n 10 mvn ...`, one build per bot at a time; prefer CI where possible.
- Swarm-stream / shared-box NOISY rule (inceptor 2026-11-01 07:05): start every leg with load1 < 3.
  A leg is **NOISY** (discard, never count) if foreign CPU averages > 0.5 core, a foreign
  java/mvn/load process is seen, or **any** sample has load1 ≥ 6. Record load1_pre/post and
  foreign_cpu judgment in the PR body; rerun NOISY legs in a quiet window. Other benches still
  use `flock /tmp/sjws-bench.lock` and prefer a quiet box before starting.

## Security

- Vulnerability reports and disclosure follow [SECURITY.md](SECURITY.md). Never describe an unfixed
  exploitable issue in a public issue, PR title or commit message.
- The control-plane admin listener is **mTLS only** (`cp.AdminServer`: client certificate required,
  identity from the certificate, `Authorization` ignored; it refuses to bind without the material).
  See [control-plane security](docs/design/control-plane-security.md) and the [audit](docs/security/audit.md).
- No secrets in the repo (keys, tokens, passwords); tests generate their own material. TLS key passwords
  come from a file or the environment, never a command-line argument.
- **Fail closed**: invalid config, missing TLS material or unknown access rules stop or refuse (exit
  64, 403), never fall back to open.

## Test hygiene

- Use JUnit `@TempDir` / temp files; never write into the repo or `HTML/`. Don't commit build output
  (`target/`), reports or benchmark artifacts.
- Tags: `h3` (needs QUIC natives), `jar` (needs the packaged jar), `e2e`, `cluster`, `bug`. See
  [testing](docs/development/testing.md) and [integration tests](docs/development/integration-tests.md).
- Known flaky: `ReloadCoverageTest.dedicatedChallengePortRebindsAndABusyPortIsRejected` has failed on
  CI with a JUnit `@TempDir` cleanup `DirectoryNotEmptyException`; rerun once if unrelated. macOS
  platform gaps are listed in [testing](docs/development/testing.md).

## Design docs

[architecture](docs/design/architecture.md) · [control plane](docs/design/control-plane.md) ·
[control-plane security](docs/design/control-plane-security.md) · [proxy-routing](docs/design/proxy-routing.md) ·
[agent swarm](docs/design/agent-swarm.md) · [messaging](docs/messaging/design.md) · [HA](docs/ha/design.md)
