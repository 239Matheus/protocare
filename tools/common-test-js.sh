#!/usr/bin/env bash
# Proto Care — compile Penpot `common` tests to JS (shadow-cljs) and run them in Node.
#
# Usage (Git Bash, from anywhere):
#   tools/common-test-js.sh                                        # whole suite
#   tools/common-test-js.sh --focus common-tests.governance.token-color-test
#
# Note: the upstream `:shadow-cljs` alias passes a JVM flag that only exists on
# JDK 23+, so this script calls shadow-cljs through the `:dev` alias instead.
# Output goes to tools/common-test-js.log.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_DIR="$(ls -d "$ROOT"/tools/jdk/jdk-25* | head -1)"

export JAVA_HOME="$(cygpath -w "$JDK_DIR" 2>/dev/null || echo "$JDK_DIR")"
export PATH="$JDK_DIR/bin:$ROOT/tools/deps-clj:$PATH"

cd "$ROOT/penpot/common"
LOG="$ROOT/tools/common-test-js.log"

if [ ! -d node_modules ]; then
  pnpm install --ignore-workspace > "$LOG" 2>&1
fi

"$ROOT/tools/deps-clj/deps.exe" -M:dev -m shadow.cljs.devtools.cli compile test >> "$LOG" 2>&1 || {
  echo "shadow-cljs build failed; see tools/common-test-js.log"; exit 1; }

node target/tests/test.js "$@" >> "$LOG" 2>&1 || true
grep -E "Ran [0-9]+ tests|[0-9]+ failures|FAIL in|ERROR in" "$LOG" || true
echo "full log: tools/common-test-js.log"
