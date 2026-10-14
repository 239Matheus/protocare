# Proto Care — compile Penpot `common` tests to JS (shadow-cljs) and run them in Node.
#
# Usage (PowerShell, from anywhere):
#   tools\common-test-js.ps1
#   tools\common-test-js.ps1 --focus common-tests.governance.token-color-test
#
# Note: the upstream `:shadow-cljs` alias passes a JVM flag that only exists on
# JDK 23+, so this script calls shadow-cljs through the `:dev` alias instead.
# Output goes to tools\common-test-js.log.

$Root = Split-Path -Parent $PSScriptRoot
$JdkDir = (Get-ChildItem -Path (Join-Path $Root "tools\jdk") -Directory -Filter "jdk-25*" | Select-Object -First 1).FullName

$env:JAVA_HOME = $JdkDir
$env:PATH = "$JdkDir\bin;$Root\tools\deps-clj;$env:PATH"

$Log = Join-Path $Root "tools\common-test-js.log"
Push-Location (Join-Path $Root "penpot\common")
try {
    if (-not (Test-Path "node_modules")) {
        pnpm install --ignore-workspace *> $Log
    }

    & "$Root\tools\deps-clj\deps.exe" -M:dev -m shadow.cljs.devtools.cli compile test *>> $Log
    if ($LASTEXITCODE -ne 0) {
        Write-Host "shadow-cljs build failed; see tools\common-test-js.log"
        exit 1
    }

    node target/tests/test.js @args *>> $Log
} finally {
    Pop-Location
}

Get-Content $Log | Select-String -Pattern "Ran [0-9]+ tests|[0-9]+ failures|FAIL in|ERROR in"
Write-Host "full log: tools\common-test-js.log"
