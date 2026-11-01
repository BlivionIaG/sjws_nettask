#!/usr/bin/env bash
# Fail when Dockerfile's jlink --add-modules list drifts from jdeps (#411).
# Usage: tools/check-jlink-modules.sh [Dockerfile] [jar] [lib-dir]
set -euo pipefail

DOCKERFILE="${1:-Dockerfile}"
JAR="${2:-}"
LIBDIR="${3:-}"

if [[ -z "$JAR" || -z "$LIBDIR" ]]; then
  echo "usage: $0 <Dockerfile> <jar> <lib-dir>" >&2
  exit 2
fi
if [[ ! -f "$DOCKERFILE" || ! -f "$JAR" || ! -d "$LIBDIR" ]]; then
  echo "error: missing Dockerfile, jar, or lib-dir" >&2
  exit 2
fi

# Modules jdeps routinely misses for this runtime (TLS/ACME, Netty DNS, zipfs, JMX).
# Keep in sync with the comment above --add-modules in the Dockerfile.
MANUAL_EXTRA=(
  # TLS / ACME providers and Netty DNS (jdeps does not see these service bindings).
  jdk.crypto.ec
  jdk.crypto.cryptoki
  jdk.naming.dns
  jdk.zipfs
  jdk.management
  # Retained even when a given jdeps run omits them: JUL bridges (Netty) and XML
  # factories used reflectively (SnakeYAML / JDK XML). Drop only with a green image smoke.
  java.logging
  java.xml
)

# Match the Docker build stage: only Linux QUIC natives are linked into the image.
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
mkdir -p "$workdir/lib"
cp -a "$LIBDIR"/. "$workdir/lib/"
rm -f "$workdir/lib"/netty-codec-native-quic-*-osx-*.jar \
      "$workdir/lib"/netty-codec-native-quic-*-windows-*.jar

# --multi-release is required: Netty ships multi-release jars.
jdeps_line="$(
  jdeps --ignore-missing-deps --multi-release 25 --print-module-deps \
    --class-path "$workdir/lib/*" "$JAR"
)"
# jdeps prints a single comma-separated line of JDK modules (and sometimes leaves a trailing newline).
jdeps_line="${jdeps_line//$'\n'/}"

parse_csv() {
  local csv="$1"
  local IFS=,
  # shellcheck disable=SC2086
  set -- $csv
  for m in "$@"; do
    m="${m// /}"
    [[ -n "$m" ]] && printf '%s\n' "$m"
  done | sort -u
}

mapfile -t jdeps_mods < <(parse_csv "$jdeps_line")

dockerfile_csv="$(
  # First --add-modules on a jlink line (the runtime image).
  awk '
    /jlink[[:space:]]*\\?$/ { in_jlink=1 }
    in_jlink && /--add-modules[[:space:]]+/ {
      line=$0
      sub(/.*--add-modules[[:space:]]+/, "", line)
      sub(/[[:space:]].*/, "", line)
      sub(/\\$/, "", line)
      print line
      exit
    }
  ' "$DOCKERFILE"
)"
if [[ -z "$dockerfile_csv" ]]; then
  echo "error: could not parse --add-modules from $DOCKERFILE" >&2
  exit 1
fi
mapfile -t docker_mods < <(parse_csv "$dockerfile_csv")

declare -A docker_set=()
for m in "${docker_mods[@]}"; do docker_set["$m"]=1; done
declare -A expected_set=()
for m in "${jdeps_mods[@]}"; do expected_set["$m"]=1; done
for m in "${MANUAL_EXTRA[@]}"; do expected_set["$m"]=1; done

missing=()
extra=()
for m in "${!expected_set[@]}"; do
  [[ -n "${docker_set[$m]+x}" ]] || missing+=("$m")
done
for m in "${!docker_set[@]}"; do
  [[ -n "${expected_set[$m]+x}" ]] || extra+=("$m")
done

IFS=$'\n'
missing_sorted="$(printf '%s\n' "${missing[@]:-}" | sed '/^$/d' | sort)"
extra_sorted="$(printf '%s\n' "${extra[@]:-}" | sed '/^$/d' | sort)"
unset IFS

echo "jdeps:      $(IFS=,; echo "${jdeps_mods[*]}")"
echo "manual:     $(IFS=,; echo "${MANUAL_EXTRA[*]}")"
echo "dockerfile: $(IFS=,; echo "${docker_mods[*]}")"

if [[ -n "$missing_sorted" || -n "$extra_sorted" ]]; then
  echo "error: Dockerfile jlink --add-modules drifted from jdeps ∪ manual extras (#411)" >&2
  [[ -n "$missing_sorted" ]] && echo "  missing from Dockerfile:"$'\n'"$missing_sorted" >&2
  [[ -n "$extra_sorted" ]] && echo "  extra in Dockerfile (not in jdeps ∪ manual):"$'\n'"$extra_sorted" >&2
  exit 1
fi
echo "OK: Dockerfile --add-modules matches jdeps ∪ manual extras."
