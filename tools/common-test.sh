#!/usr/bin/env bash
# Proto Care — run Penpot `common` tests on the JVM with the portable toolchain.
#
# Usage (Git Bash, from anywhere):
#   tools/common-test.sh                                   # whole suite
#   tools/common-test.sh --focus common-tests.governance.token-color-test
#
# Output goes to tools/common-test.log (never pipe test output to filters).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_DIR="$(ls -d "$ROOT"/tools/jdk/jdk-25* | head -1)"

export JAVA_HOME="$(cygpath -w "$JDK_DIR" 2>/dev/null || echo "$JDK_DIR")"
export PATH="$JDK_DIR/bin:$ROOT/tools/deps-clj:$PATH"

cd "$ROOT/penpot/common"
"$ROOT/tools/deps-clj/deps.exe" -M:dev:test "$@" > "$ROOT/tools/common-test.log" 2>&1 || true
grep -v "^Downloading" "$ROOT/tools/common-test.log" | grep -E "tests,|FAIL in|ERROR in" || true
echo "full log: tools/common-test.log"
