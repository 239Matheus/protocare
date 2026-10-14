# Proto Care — Penpot devenv wrapper for Windows.
#
# Runs penpot/manage.sh through Git Bash with the environment the devenv
# needs on this machine (Docker CLI path, Windows-form source path).
#
# Usage (PowerShell, from anywhere):
#   tools\devenv.ps1 up        # start infra + main container (compiles inside tmux)
#   tools\devenv.ps1 attach    # attach to the tmux session (needs an interactive terminal)
#   tools\devenv.ps1 status    # tmux windows + last lines of the frontend build
#   tools\devenv.ps1 recompile # rebuild frontend code, CSS and translations after host edits
#   tools\devenv.ps1 down      # stop the main container (infra stays up)
#   tools\devenv.ps1 down-all  # stop everything (data volumes are kept)
#
# UI: https://localhost:3449 (self-signed certificate) or http://localhost:3450

param([string]$Command = "status")

$Root = Split-Path -Parent $PSScriptRoot
$DockerBin = if ($env:DOCKER_BIN) { $env:DOCKER_BIN } else { "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin" }
$GitBash = "C:\Program Files\Git\bin\bash.exe"

$env:PATH = "$DockerBin;$env:PATH"
$env:PENPOT_SOURCE_PATH = (Join-Path $Root "penpot") -replace "\\", "/"
$env:MSYS_NO_PATHCONV = "1"

# The frontend reads its feature flags from this gitignored file.
$ConfigJs = Join-Path $Root "penpot\frontend\resources\public\js\config.js"
if (-not (Test-Path $ConfigJs)) {
    New-Item -ItemType Directory -Force (Split-Path $ConfigJs) | Out-Null
    Set-Content -Path $ConfigJs -Encoding ascii -Value 'var penpotFlags = "enable-governance enable-demo-users disable-onboarding disable-onboarding-questions disable-onboarding-newsletter";'
}

# Local accounts: skip email verification (the devenv delivers mail to
# Mailpit at http://localhost:1080, never to real inboxes).
$EnvLocal = Join-Path $Root "penpot\backend\scripts\_env.local"
if (-not (Test-Path $EnvLocal)) {
    $lines = @(
        '# Local-only overrides for the devenv backend (gitignored).',
        '# Proto Care: skip email verification so local accounts work without',
        '# opening Mailpit (http://localhost:1080).',
        'export PENPOT_FLAGS="$PENPOT_FLAGS disable-email-verification"'
    )
    [System.IO.File]::WriteAllText($EnvLocal, (($lines -join "`n") + "`n"))
}

function Invoke-Manage([string]$ManageArgs) {
    Push-Location (Join-Path $Root "penpot")
    try {
        & $GitBash -lc "export PATH=`"/c/Users/$env:USERNAME/AppData/Local/Programs/DockerDesktop/resources/bin:`$PATH`"; ./manage.sh $ManageArgs"
    } finally {
        Pop-Location
    }
}

switch ($Command) {
    "up"       { Invoke-Manage "run-devenv" }
    "attach"   { Invoke-Manage "attach-devenv" }
    "recompile" {
        # Docker Desktop bind mounts on Windows do not forward file events, so
        # the shadow-cljs and asset watchers never see edits made on the host.
        $Container = "penpot-devenv-ws0-main"
        & docker cp (Join-Path $PSScriptRoot "nrepl_eval.py") "${Container}:/tmp/nrepl_eval.py"
        Write-Host "--- frontend code (shadow-cljs :main) ---"
        & docker exec $Container python3 /tmp/nrepl_eval.py 3447 "(do (require (quote shadow.cljs.devtools.api)) (str (shadow.cljs.devtools.api/compile :main)))"
        Write-Host "--- CSS, templates and translations ---"
        & docker exec $Container sudo -u penpot bash -lc "cd ~/penpot/frontend && node ./scripts/build-app-assets.js"
        # css/ui.css (styles of the DS menu and modal, from packages/ui) is
        # not produced by build-app-assets; without it menus render unstyled.
        Write-Host "--- packages/ui (css/ui.css), only when missing ---"
        & docker exec $Container sudo -u penpot bash -lc "cd ~/penpot/frontend && (test -f resources/public/css/ui.css || (cd packages/ui && pnpm run build > /tmp/ui-build.txt 2>&1 && echo built ui.css))"
        Write-Host "Done. Reload the browser tab."
    }
    "down"     { Invoke-Manage "stop-devenv" }
    "down-all" { Invoke-Manage "stop-devenv --all" }
    "status"   {
        & docker ps --filter "name=penpot" --format "table {{.Names}}\t{{.Status}}"
        & docker exec penpot-devenv-ws0-main sudo -u penpot tmux list-windows -t penpot
        Write-Host "--- frontend watch (last lines) ---"
        & docker exec penpot-devenv-ws0-main sudo -u penpot tmux capture-pane -p -t penpot:0 -S -40
    }
    default    { Write-Host "unknown command '$Command' (up|attach|status|down|down-all)" }
}
