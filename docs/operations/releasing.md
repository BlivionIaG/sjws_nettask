# Releasing sjws_nettask

Releases are cut by pushing a version tag; `.github/workflows/release.yml` does the rest.
See also [CONTRIBUTING.md](../../CONTRIBUTING.md) and the [README](../../README.md).

    git checkout master && git pull
    git tag -a v0.2.0 -m "sjws_nettask 0.2.0"
    git push origin v0.2.0

The workflow (#410):

1. sets the Maven version from the tag (`v0.2.0` -> `0.2.0`; the pom on master stays `-SNAPSHOT`),
2. runs `mvn -B verify` on `ubuntu-latest` (all tests, the `jar` tests against the built jar, JaCoCo gate),
3. packages on `ubuntu-latest`, `windows-latest` and `macos-latest` (`tools/package-release.sh`):
   * a per-OS bundle `sjws_nettask-<version>-<os>-<arch>.tar.gz` / `.zip` with the jar,
     `lib/` (Netty + the linux/osx/windows QUIC native jars already pulled by the HTTP/3 POM),
     a `jlink` runtime built from the Dockerfile `--add-modules` list, LICENSE, README,
     `contrib/` (systemd units, drop-in, role samples, `sjws` start/stop script; see
     [control-plane-service.md](control-plane-service.md)), and `sjws_nettask` / `sjws_nettask.cmd`
     launchers that use the bundled runtime,
   * on Linux, the historical portable tree as well: `sjws_nettask-<version>.tar.gz` / `.zip` and
     the bare `sjws_nettask-<version>.jar` (needs `lib/` next to it, see the manifest Class-Path;
     requires a local Java 25+),
   * `SHA256SUMS` (merged from per-platform `SHA256SUMS-<os>-<arch>` files).
4. On a real tag only, publishes a GitHub Release with auto-generated notes (PRs merged since
   the previous tag) and those artifacts. `workflow_dispatch` never reaches this step.

The `jlink` image is the cross-OS binary. This repository has no code-signing identity, so
unsigned `jpackage` `.msi` / `.dmg` installers are not produced. Hosted runners cover
linux-x86_64, windows-x86_64 and whichever macOS arch GitHub provides (today typically
aarch64); other QUIC native jars still ship in `lib/` of every bundle.

Tags with a suffix (`v0.2.0-rc.1`) are published as pre-releases. Run a jlink bundle with
`./sjws_nettask --help`, or the portable tree with
`tar xzf sjws_nettask-<version>.tar.gz && java -jar sjws_nettask-<version>/sjws_nettask-<version>.jar --help`
(Java 25+). Release notes can be edited on GitHub afterwards. A failed run publishes nothing;
fix, delete and re-push the tag. Real `v*` tags need Blivion's OK; never push one only to test CI.

## Dry run ([#413](https://github.com/BlivionIaG/sjws_nettask/issues/413), [#410](https://github.com/BlivionIaG/sjws_nettask/issues/410))

`release.yml` also accepts `workflow_dispatch` with a `version` input (default
`0.0.0-SNAPSHOT`). That path runs the same verify + three-OS package steps, uploads the
artifacts, and **does not** create a GitHub Release or require a tag. Use it before the
next real tag, after the workflow file with `workflow_dispatch` is on the branch you
dispatch:

    gh workflow run release.yml --ref feat/410-multi-os-release -f version=0.0.0-SNAPSHOT

On master, after this change is merged:

    gh workflow run release.yml --ref master -f version=0.0.0-SNAPSHOT

Do not create a throwaway `v*` tag on `BlivionIaG/sjws_nettask` for this; a fork tag
is the only tag-based alternative, and the dispatch path is preferred.
