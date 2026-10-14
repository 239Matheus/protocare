# Proto Care — run Penpot `backend` tests inside a Linux container.
#
# The backend uses POSIX file permissions (datoteka), so it cannot run on a
# Windows JVM. This runs the official Clojure tools-deps image on the same
# Docker network as tools\infra\docker-compose.yml (service names `postgres`
# and `valkey`). Maven/git deps are cached in a named volume.
#
# Usage (PowerShell, from anywhere):
#   tools\backend-test-docker.ps1
#   tools\backend-test-docker.ps1 --focus backend-tests.rpc-governance-test
#
# Output goes to tools\backend-test.log.

$Root = Split-Path -Parent $PSScriptRoot
$DockerBin = if ($env:DOCKER_BIN) { $env:DOCKER_BIN } else { "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin" }
$env:PATH = "$DockerBin;$env:PATH"

$Image = "clojure:temurin-25-tools-deps"
$Network = "protocare-infra_default"
$Log = Join-Path $Root "tools\backend-test.log"
$Repo = Join-Path $Root "penpot"

& docker run --rm `
  --network $Network `
  -v "${Repo}:/penpot" `
  -v protocare_clj_cache:/root/.m2 `
  -v protocare_gitlibs_cache:/root/.gitlibs `
  -w /penpot/backend `
  -e PENPOT_FLAGS=enable-backend-asserts `
  -e PENPOT_TEST_DATABASE_URI=postgresql://postgres/penpot_test `
  -e PENPOT_TEST_REDIS_URI=redis://valkey/1 `
  $Image `
  clojure -M:dev:test @args *> $Log

Get-Content $Log | Where-Object { $_ -notmatch "^Downloading" } | Select-String -Pattern "tests,|FAIL in|ERROR in|refused|Exception"
Write-Host "full log: tools\backend-test.log"
