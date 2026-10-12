#!/usr/bin/env bash
# One-command footprint benchmark: builds the jar and runs footprint.py.
# Usage: bench/footprint/run.sh [footprint.py options]   (needs JDK 25, Maven, python3, wrk)
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
mvn -B -q package -DskipTests
exec python3 bench/footprint/footprint.py --java-home "$JAVA_HOME" "$@"
