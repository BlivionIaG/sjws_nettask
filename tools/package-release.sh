#!/usr/bin/env bash
# Build a per-OS release tree: portable jar+lib (all Netty QUIC natives), a
# jlink runtime from the Dockerfile --add-modules list, LICENSE/README/contrib.
# Usage: tools/package-release.sh <version> <dest-dir>
# The dest-dir receives archives and SHA256SUMS. Never publishes or creates tags.
set -euo pipefail

zip_tree() {
  # $1 = parent dir, $2 = tree name, $3 = destination zip
  python3 - "$1" "$2" "$3" << 'PY'
import shutil, sys
from pathlib import Path
parent, tree, dest = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
if dest.exists():
    dest.unlink()
shutil.make_archive(str(dest.with_suffix("")), "zip", parent, tree)
PY
}


if [[ $# -lt 2 ]]; then
  echo "usage: $0 <version> <dest-dir>" >&2
  exit 2
fi

VERSION="$1"
DEST="$(mkdir -p "$2" && cd "$2" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/target/sjws_nettask-$VERSION.jar"
LIBDIR="$ROOT/target/lib"
DOCKERFILE="$ROOT/Dockerfile"

if [[ ! -f "$JAR" || ! -d "$LIBDIR" ]]; then
  echo "error: missing $JAR or $LIBDIR; run mvn -B package first with this version" >&2
  exit 2
fi

os="$(uname -s)"
arch="$(uname -m)"
case "$os" in
  Linux) platform="linux" ;;
  Darwin) platform="macos" ;;
  MINGW*|MSYS*|CYGWIN*|Windows_NT) platform="windows" ;;
  *) echo "error: unsupported uname $os" >&2; exit 2 ;;
esac
case "$arch" in
  x86_64|amd64|AMD64) arch="x86_64" ;;
  aarch64|arm64) arch="aarch64" ;;
  *) echo "error: unsupported arch $arch" >&2; exit 2 ;;
esac
platform="${platform}-${arch}"

name="sjws_nettask-$VERSION"
bundle="${name}-${platform}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tree="$work/$bundle"
mkdir -p "$tree/lib" "$tree/contrib"

cp "$JAR" "$tree/"
cp -R "$LIBDIR"/. "$tree/lib/"
cp "$ROOT/LICENSE" "$ROOT/README.md" "$tree/"
cp "$ROOT/contrib/"*.service "$ROOT/contrib/"*.yaml "$ROOT/contrib/"*.conf "$ROOT/contrib/sjws" "$tree/contrib/"

modules="$(
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
if [[ -z "$modules" ]]; then
  echo "error: could not parse --add-modules from $DOCKERFILE" >&2
  exit 1
fi

jlink_bin="${JAVA_HOME:+$JAVA_HOME/bin/}jlink"
"$jlink_bin" \
  --add-modules "$modules" \
  --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
  --output "$tree/jre"

# Launchers use the bundled runtime; --enable-native-access is required for QUIC natives.
cat > "$tree/sjws_nettask" << LAUNCH
#!/usr/bin/env bash
set -euo pipefail
here="\$(cd "\$(dirname "\$0")" && pwd)"
exec "\$here/jre/bin/java" --enable-native-access=ALL-UNNAMED -jar "\$here/$name.jar" "\$@"
LAUNCH
chmod +x "$tree/sjws_nettask"

if [[ "$platform" == windows-* ]]; then
  cat > "$tree/sjws_nettask.cmd" << LAUNCH
@echo off
setlocal
set HERE=%~dp0
"%HERE%jre\\bin\\java.exe" --enable-native-access=ALL-UNNAMED -jar "%HERE%$name.jar" %*
LAUNCH
fi

# zip is available on ubuntu, macos, and the GitHub Windows runner (via the
# packaged zip or powershell). Prefer zip(1); fall back to python.
archive_zip="$DEST/$bundle.zip"
zip_tree "$work" "$bundle" "$archive_zip"

archives=("$archive_zip")
if [[ "$platform" != windows-* ]]; then
  archive_tar="$DEST/$bundle.tar.gz"
  tar -C "$work" -czf "$archive_tar" "$bundle"
  archives+=("$archive_tar")
fi

# On linux also keep the historical portable tree (jar+lib, no bundled JRE).
if [[ "$platform" == linux-* ]]; then
  portable="$work/$name"
  mkdir -p "$portable/lib" "$portable/contrib"
  cp "$JAR" "$portable/"
  cp -R "$LIBDIR"/. "$portable/lib/"
  cp "$ROOT/LICENSE" "$ROOT/README.md" "$portable/"
  cp "$ROOT/contrib/"*.service "$ROOT/contrib/"*.yaml "$ROOT/contrib/"*.conf "$ROOT/contrib/sjws" "$portable/contrib/"
  tar -C "$work" -czf "$DEST/$name.tar.gz" "$name"
  zip_tree "$work" "$name" "$DEST/$name.zip"
  cp "$JAR" "$DEST/"
  archives+=("$DEST/$name.tar.gz" "$DEST/$name.zip" "$DEST/$name.jar")
fi

python3 - "$DEST" "$platform" "${archives[@]}" << 'PY'
import hashlib, sys
from pathlib import Path
dest = Path(sys.argv[1])
platform = sys.argv[2]
lines = []
for raw in sys.argv[3:]:
    path = Path(raw)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    lines.append(f"{digest}  {path.name}")
text = "\n".join(lines) + "\n"
(dest / f"SHA256SUMS-{platform}").write_text(text, encoding="utf-8")
print(text, end="")
PY

echo "platform=$platform"
echo "bundled ${#archives[@]} archive(s) in $DEST"
