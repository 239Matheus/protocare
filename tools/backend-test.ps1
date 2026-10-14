# Proto Care — run Penpot `backend` tests on the JVM with the portable toolchain.
#
# Requires PostgreSQL + Valkey on localhost: tools\infra\docker-compose.yml
#   docker compose -p protocare-infra -f tools/infra/docker-compose.yml up -d --wait
#
# Usage (PowerShell, from anywhere):
#   tools\backend-test.ps1
#   tools\backend-test.ps1 --focus backend-tests.rpc-governance-test
#
# Output goes to tools\backend-test.log.

$Root = Split-Path -Parent $PSScriptRoot
$JdkDir = (Get-ChildItem -Path (Join-Path $Root "tools\jdk") -Directory -Filter "jdk-25*" | Select-Object -First 1).FullName

$env:JAVA_HOME = $JdkDir
$env:PATH = "$JdkDir\bin;$Root\tools\deps-clj;$env:PATH"
$env:PENPOT_FLAGS = "enable-backend-asserts"
if (-not $env:PENPOT_TEST_DATABASE_URI) { $env:PENPOT_TEST_DATABASE_URI = "postgresql://localhost:15432/penpot_test" }
if (-not $env:PENPOT_TEST_REDIS_URI) { $env:PENPOT_TEST_REDIS_URI = "redis://localhost:6379/1" }

$Log = Join-Path $Root "tools\backend-test.log"
Push-Location (Join-Path $Root "penpot\backend")
try {
    & "$Root\tools\deps-clj\deps.exe" -M:dev:test @args *> $Log
} finally {
    Pop-Location
}

Get-Content $Log | Where-Object { $_ -notmatch "^Downloading" } | Select-String -Pattern "tests,|FAIL in|ERROR in|Connection refused|Connection to .* refused"
Write-Host "full log: tools\backend-test.log"
