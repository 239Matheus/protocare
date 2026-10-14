#!/usr/bin/env bash
# Proto Care — run Penpot `backend` tests on the JVM with the portable toolchain.
#
# Requires PostgreSQL + Valkey on localhost: tools/infra/docker-compose.yml
#   docker compose -p protocare-infra -f tools/infra/docker-compose.yml up -d --wait
#
# Usage (Git Bash, from anywhere):
#   tools/backend-test.sh
#   tools/backend-test.sh --focus backend-tests.rpc-governance-test
#
# Output goes to tools/backend-test.log.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_DIR="$(ls -d "$ROOT"/tools/jdk/jdk-25* | head -1)"

export JAVA_HOME="$(cygpath -w "$JDK_DIR" 2>/dev/null || echo "$JDK_DIR")"
export PATH="$JDK_DIR/bin:$ROOT/tools/deps-clj:$PATH"
export PENPOT_FLAGS="enable-backend-asserts"
export PENPOT_TEST_DATABASE_URI="${PENPOT_TEST_DATABASE_URI:-postgresql://localhost:15432/penpot_test}"
export PENPOT_TEST_REDIS_URI="${PENPOT_TEST_REDIS_URI:-redis://localhost:6379/1}"

cd "$ROOT/penpot/backend"
"$ROOT/tools/deps-clj/deps.exe" -M:dev:test "$@" > "$ROOT/tools/backend-test.log" 2>&1 || true
grep -v "^Downloading" "$ROOT/tools/backend-test.log" | grep -E "tests,|FAIL in|ERROR in|refused" || true
echo "full log: tools/backend-test.log"
