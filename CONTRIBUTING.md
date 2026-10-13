# Contributing to sjws_nettask

Coding agents: read [AGENTS.md](AGENTS.md) first (rules for authors, PRs, perf numbers, security), and
[docs/development/ownership.md](docs/development/ownership.md) for who owns which area.

## Setup

Java 25+ and Maven. Sources are in `src/` (NetBeans layout), tests in `tests/`.

    mvn verify                        # tests, the jar tests, JaCoCo coverage gate
    mvn test -DexcludedGroups=h3      # without QUIC natives

Details (tags, coverage gates, platform gaps, mutation testing): [docs/development/testing.md](docs/development/testing.md).
Code layout: [docs/design/architecture.md](docs/design/architecture.md).

## Pull requests

* Branch off `master` and open a PR against it. CI (`.github/workflows/ci.yml`) runs `mvn -B verify`
  on Ubuntu with Java 25 and must be green.
* Add a test for every behaviour change; new regressions can start as `@Tag("bug")` tests.
* A new `--flag` or `WEB_SERVER_*` variable must appear in `--help` **and** in an option table under docs/ (see `DocsConsistencyTest.OPTION_DOCS`)
  with its default: `DocsConsistencyTest` fails otherwise. A new source package needs a JaCoCo gate.
* Respect the boundaries `SourceGuardTest` checks: no `synchronized`, no Netty in `core` or HTTP/1,
  `h3` doesn't import HTTP/1. Prefer extending behaviour through `core.Handler` rather than inside
  a transport.
* Keep PRs small and avoid moving/renaming files, so parallel work rebases cleanly.
* Update the docs you affect: [docs/](docs/index.md) (user-visible behaviour and options), the short [README](README.md),
  [docs/user/configuration.md](docs/user/configuration.md), [docs/design/architecture.md](docs/design/architecture.md),
  [docs/development/testing.md](docs/development/testing.md) (new test classes).

Releases: see [docs/operations/releasing.md](docs/operations/releasing.md).
