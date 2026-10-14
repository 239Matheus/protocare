# Proto Care — stop PostgreSQL + Valkey (data volumes are kept).
#
# Usage (PowerShell, from anywhere):
#   tools\infra-down.ps1

$Root = Split-Path -Parent $PSScriptRoot
$DockerBin = if ($env:DOCKER_BIN) { $env:DOCKER_BIN } else { "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin" }
$env:PATH = "$DockerBin;$env:PATH"

& docker compose -p protocare-infra -f (Join-Path $Root "tools\infra\docker-compose.yml") down
