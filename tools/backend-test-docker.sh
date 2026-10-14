#!/usr/bin/env bash
# Proto Care — run Penpot `backend` tests inside a Linux container.
#
# The backend uses POSIX file permissions (datoteka), so it cannot run on a
# Windows JVM. This runs the official Clojure tools-deps image on the same
# Docker network as tools/infra/docker-compose.yml (service names `postgres`
# and `valkey`). Maven/git deps are cached in a named volume.
#
# Usage (Git Bash, from anywhere):
#   tools/backend-test-docker.sh
#   tools/backend-test-docker.sh --focus backend-tests.rpc-governance-test
#
# Output goes to tools/backend-test.log.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER_BIN="${DOCKER_BIN:-/c/Users/mathe/AppData/Local/Programs/DockerDesktop/resources/bin}"
export PATH="$DOCKER_BIN:$PATH"
export MSYS_NO_PATHCONV=1

IMAGE="clojure:temurin-25-tools-deps"
NETWORK="protocare-infra_default"
LOG="$ROOT/tools/backend-test.log"
REPO_WIN="$(cygpath -w "$ROOT/penpot" 2>/dev/null || echo "$ROOT/penpot")"

docker run --rm \
  --network "$NETWORK" \
  -v "${REPO_WIN}:/penpot" \
  -v protocare_clj_cache:/root/.m2 \
  -v protocare_gitlibs_cache:/root/.gitlibs \
  -w /penpot/backend \
  -e PENPOT_FLAGS="enable-backend-asserts" \
  -e PENPOT_TEST_DATABASE_URI="postgresql://postgres/penpot_test" \
  -e PENPOT_TEST_REDIS_URI="redis://valkey/1" \
  "$IMAGE" \
  clojure -M:dev:test "$@" > "$LOG" 2>&1 || true

grep -v "^Downloading" "$LOG" | grep -E "tests,|FAIL in|ERROR in|refused|Exception" || true
echo "full log: tools/backend-test.log"
