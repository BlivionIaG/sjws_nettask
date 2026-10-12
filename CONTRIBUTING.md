# Contributing to sjws_nettask

## Setup

Java 25+ and Maven. Sources are in `src/` (NetBeans layout), tests in `tests/`.

    mvn verify                        # tests, the jar tests, JaCoCo coverage gate
    mvn test -DexcludedGroups=h3      # without QUIC natives

Details (tags, coverage gates, platform gaps, mutation testing): [tests/README.md](tests/README.md).
Code layout: [ARCHITECTURE.md](ARCHITECTURE.md).

## Pull requests

* Branch off `master` and open a PR against it. CI (`.github/workflows/ci.yml`) runs `mvn -B verify`
  on Ubuntu with Java 25 and must be green.
* Add a test for every behaviour change; new regressions can start as `@Tag("bug")` tests.
* A new `--flag` or `WEB_SERVER_*` variable must appear in `--help` **and** in a README option table
  with its default: `DocsConsistencyTest` fails otherwise. A new source package needs a JaCoCo gate.
* Respect the boundaries `SourceGuardTest` checks: no `synchronized`, no Netty in `core` or HTTP/1,
  `h3` doesn't import HTTP/1. Prefer extending behaviour through `core.Handler` rather than inside
  a transport.
* Keep PRs small and avoid moving/renaming files, so parallel work rebases cleanly.
* Update the docs you affect: [README](README.md) (user-visible behaviour and options),
  [docs/configuration.md](docs/configuration.md), [ARCHITECTURE.md](ARCHITECTURE.md),
  [tests/README.md](tests/README.md) (new test classes).

Releases: see [RELEASING.md](RELEASING.md).
