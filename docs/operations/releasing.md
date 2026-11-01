# Releasing sjws_nettask

Releases are cut by pushing a version tag; `.github/workflows/release.yml` does the rest.
See also [CONTRIBUTING.md](../../CONTRIBUTING.md) and the [README](../../README.md).

    git checkout master && git pull
    git tag -a v0.2.0 -m "sjws_nettask 0.2.0"
    git push origin v0.2.0

The workflow:

1. sets the Maven version from the tag (`v0.2.0` -> `0.2.0`; the pom on master stays `-SNAPSHOT`),
2. runs `mvn -B verify` (all tests, the `jar` tests against the built jar, JaCoCo gate),
3. publishes a GitHub Release with auto-generated notes (PRs merged since the previous tag) and:
   * `sjws_nettask-<version>.tar.gz` / `.zip`: the jar, `lib/` (Netty + QUIC natives), LICENSE, README, and
     `contrib/`: the systemd units (`sjws_nettask.service`, `sjws_nettask-control@.service`,
     `sjws_nettask-data@.service`), the drop-in `sjws_nettask-data-same-host.conf`, the role samples
     `sjws_nettask-{standalone,control,data}.yaml` and the `sjws` start/stop script
     (see [control-plane-service.md](control-plane-service.md))
   * `sjws_nettask-<version>.jar`: the bare jar (needs `lib/` next to it, see the manifest Class-Path)
   * `SHA256SUMS`

Tags with a suffix (`v0.2.0-rc.1`) are published as pre-releases. Run with
`tar xzf sjws_nettask-<version>.tar.gz && java -jar sjws_nettask-<version>/sjws_nettask-<version>.jar --help`
(Java 25+). Release notes can be edited on GitHub afterwards. A failed run publishes nothing;
fix, delete and re-push the tag. Real `v*` tags need Blivion's OK; never push one only to test CI.

## Dry run on ubuntu-latest ([#413](https://github.com/BlivionIaG/sjws_nettask/issues/413))

`release.yml` also accepts `workflow_dispatch` with a `version` input (default
`0.0.0-dryrun`). That path runs the same verify + package steps on `ubuntu-latest`,
uploads the artifacts, and **does not** create a GitHub Release or require a tag.
Use it before the next real tag, after the workflow file with `workflow_dispatch` is
on the branch you dispatch:

    gh workflow run release.yml --ref master -f version=0.0.0-dryrun

Do not create a throwaway `v*` tag on `BlivionIaG/sjws_nettask` for this; a fork tag
is the only tag-based alternative, and the dispatch path is preferred.
