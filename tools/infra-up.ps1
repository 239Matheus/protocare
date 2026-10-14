# Proto Care — start PostgreSQL + Valkey for the backend tests.
# Adds the Docker Desktop CLI to PATH for this session, so it works even in
# terminals opened before Docker was installed.
#
# Usage (PowerShell, from anywhere):
#   tools\infra-up.ps1

$Root = Split-Path -Parent $PSScriptRoot
$DockerBin = if ($env:DOCKER_BIN) { $env:DOCKER_BIN } else { "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin" }
$env:PATH = "$DockerBin;$env:PATH"

& docker compose -p protocare-infra -f (Join-Path $Root "tools\infra\docker-compose.yml") up -d --wait --wait-timeout 180
& docker compose -p protocare-infra -f (Join-Path $Root "tools\infra\docker-compose.yml") ps
