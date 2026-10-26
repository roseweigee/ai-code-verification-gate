#!/usr/bin/env bash
# Builds with the plain JDK (no dependency download) and runs the CLI.
# Requires JDK 17+. Usage: ./run.sh demo | run --attempt N [--live] | approve --by NAME | audit | selftest
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes
javac -d build/classes --release 17 $(find src/main/java -name '*.java')
exec java -cp build/classes -Daiverify.root=. dev.aiverify.Main "$@"
