#!/usr/bin/env bash
# Control-plane benchmarks. Usage: bench/controlplane/run.sh [cp.py options]  (JDK 25, Maven, python3, wrk, wrk2)
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
mvn -B -q package -DskipTests
exec python3 bench/controlplane/cp.py --java-home "$JAVA_HOME" "$@"
